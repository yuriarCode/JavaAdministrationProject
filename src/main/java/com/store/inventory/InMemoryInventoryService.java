package com.store.inventory;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.api.StockAlertListener;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-memory implementation of the reservation service.
 *
 * NEW. Key decisions (details and alternatives are in DECISIONS.md):
 * 
 * Idempotency:</b> the app resends orders on slow connections, so {@code reserve} with the
 *       same order and the same data returns the original reservation instead of holding units
 *       again; a repeated {@code confirm} is a no-op.</li>
 *   Concurrency:</b> one lock per product ({@code synchronized (product)}); the orders map is
 *       concurrent and is written atomically. Units are never oversold.</li>
 *   Lazy expiration:</b> no timers; expired reservations are released when the product is
 *       touched, always using the injected {@link Clock} (testable without {@code sleep}).</li>
 *   Alerts outside the lock:</b> the listener (email, etc.) may be slow or fail; it is never
 *       called while holding the lock and it cannot break a reservation that was already made.</li>
 * 
 */
final class InMemoryInventoryService implements InventoryService {

    private static final Logger LOG = Logger.getLogger(InMemoryInventoryService.class.getName());

    /** Default threshold for the purchasing alert: "5 available units or fewer". */
    static final int DEFAULT_LOW_STOCK_THRESHOLD = 5;

    private final Clock clock;
    private final StockAlertListener alertListener;
    private final CategoryPolicies policies;
    private final int lowStockThreshold;

    private final ConcurrentMap<String, ProductStock> products = new ConcurrentHashMap<>();
    /** orderId -> its most recent reservation. Used to confirm and to detect retries. */
    private final ConcurrentMap<String, ReservationRecord> orders = new ConcurrentHashMap<>();

    InMemoryInventoryService(Clock clock, StockAlertListener alertListener,
                             CategoryPolicies policies, int lowStockThreshold) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.alertListener = Objects.requireNonNull(alertListener, "alertListener");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.lowStockThreshold = lowStockThreshold;
    }

    @Override
    public void registerProduct(String sku, ProductCategory category) {
        requireText(sku, "sku");
        Objects.requireNonNull(category, "category");
        // Fails here (ISE) if the category has no policy, BEFORE registering anything.
        CategoryPolicy policy = policies.forCategory(category);

        products.compute(sku, (key, existing) -> {
            if (existing == null) {
                return new ProductStock(sku, category, policy);
            }
            // Registering twice with the same category is harmless (idempotent). Changing it while
            // reservations are in flight would alter their rules midway, so it is rejected.
            if (existing.category() != category) {
                throw new IllegalArgumentException("Product " + sku + " is already registered as "
                        + existing.category() + "; it cannot be changed to " + category);
            }
            return existing;
        });
    }

    @Override
    public void addStock(String sku, int quantity) {
        requireText(sku, "sku");
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive: " + quantity);
        }
        ProductStock product = products.get(sku);
        if (product == null) {
            throw new IllegalArgumentException("Product not registered: " + sku);
        }
        synchronized (product) {
            product.purgeExpired(clock.instant());
            product.addStock(quantity);
            // Restocking re-arms the alert: the next time stock drops to the threshold, purchasing is told.
            product.rearmAlert();
        }
    }

    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        requireText(orderId, "orderId");
        requireText(sku, "sku");
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive: " + quantity);
        }
        ProductStock product = products.get(sku);
        if (product == null) {
            // Contract: an unknown product "has no units".
            throw new InsufficientStockException(sku, quantity, 0);
        }

        Reservation result;
        Integer alertAvailable = null; // != null => an alert must be sent (done outside the lock)

        synchronized (product) {
            // The time is read INSIDE the lock so that the order of operations matches the clock.
            Instant now = clock.instant();
            product.purgeExpired(now);
            product.rearmAlertIfAbove(lowStockThreshold);

            // 1) App retry: if the order already has a live reservation, do not hold units again.
            ReservationRecord existing = orders.get(orderId);
            if (existing != null && existing.isLiveAt(now)) {
                if (existing.matches(sku, quantity)) {
                    return existing.reservation();
                }
                throw new IllegalArgumentException("Order " + orderId
                        + " already has a reservation with different data (sku/quantity)");
            }
            // If existing is expired, this attempt simply replaces it.

            // 2) Category rules.
            CategoryPolicy policy = product.policy();
            if (policy.exceedsOrderLimit(quantity)) {
                throw new OrderLimitExceededException(sku, quantity, policy.maxUnitsPerOrder().getAsInt());
            }

            // 3) Availability.
            int available = product.available();
            if (quantity > available) {
                throw new InsufficientStockException(sku, quantity, available);
            }

            // 4) Register the order atomically BEFORE holding units, so no units are left held if
            //    another thread reserved the same orderId on a different SKU.
            Reservation reservation = new Reservation(orderId, sku, quantity, now.plus(policy.paymentWindow()));
            ReservationRecord record = new ReservationRecord(reservation, product);
            boolean registered = (existing == null)
                    ? orders.putIfAbsent(orderId, record) == null
                    : orders.replace(orderId, existing, record);
            if (!registered) {
                throw new IllegalArgumentException("Order " + orderId
                        + " is being reserved concurrently with different data");
            }

            product.hold(record);
            if (product.claimLowStockAlert(lowStockThreshold)) {
                alertAvailable = product.available();
            }
            result = reservation;
        }

        if (alertAvailable != null) {
            notifyLowStock(product, alertAvailable);
        }
        return result;
    }

    @Override
    public void confirm(String orderId) {
        requireText(orderId, "orderId");
        while (true) {
            ReservationRecord record = orders.get(orderId);
            if (record == null) {
                throw new IllegalStateException("Order has no active reservation: " + orderId);
            }
            ProductStock product = record.product();
            synchronized (product) {
                if (orders.get(orderId) != record) {
                    continue; // the order was re-reserved while we waited for the lock: retry
                }
                product.purgeExpired(clock.instant());
                switch (record.state()) {
                    case CONFIRMED:
                        return; // repeated confirmation (e.g. a resent payment webhook): no-op
                    case PENDING:
                        product.confirm(record);
                        return;
                    default: // EXPIRED
                        throw new IllegalStateException("The reservation of order " + orderId + " has expired");
                }
            }
        }
    }

    @Override
    public int available(String sku) {
        ProductStock product = sku == null ? null : products.get(sku);
        if (product == null) {
            return 0;
        }
        synchronized (product) {
            product.purgeExpired(clock.instant());
            product.rearmAlertIfAbove(lowStockThreshold);
            return product.available();
        }
    }

    /**
     * Delivers the alert to purchasing. Runs OUTSIDE the lock and isolates listener failures:
     * if it fails, the reservation already made stays valid and the alert is re-armed so it is
     * retried on the next operation (a late alert is better than a lost one).
     */
    private void notifyLowStock(ProductStock product, int availableUnits) {
        try {
            alertListener.onLowStock(product.sku(), availableUnits);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Low-stock alert for " + product.sku() + " failed; it will be retried", e);
            synchronized (product) {
                product.rearmAlert();
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
