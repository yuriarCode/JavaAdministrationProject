package com.store.inventory;

import com.store.inventory.api.ProductCategory;
import java.time.Instant;
import java.util.PriorityQueue;

/**
 * Inventory state of ONE product: units in the warehouse, reserved units, and the state of the
 * low-stock alert.
 *
 * NEW. <b>Concurrency:</b> this class is NOT thread-safe on its own. Every access must happen
 * inside {@code synchronized (product)}. There is one lock per product (instead of a global one)
 * so that selling a popular SKU does not block the rest of the catalog.
 *
 * Unit model:
 * <ul>
 *   <li>{@code stock}: physical units not yet sold (only decreased on confirm).</li>
 *   <li>{@code reserved}: sum of the live PENDING reservations.</li>
 *   <li>{@code available = stock - reserved}.</li>
 * </ul>
 *
 * Expired reservations are released lazily ({@link #purgeExpired}) every time the product is
 * touched, so no background thread/timer is needed.
 */
final class ProductStock {

    private final String sku;
    private final ProductCategory category;
    private final CategoryPolicy policy;

    private int stock;
    private int reserved;

    /** PENDING reservations ordered by expiry: purging costs O(k log n), k = expired ones. */
    private final PriorityQueue<ReservationRecord> byExpiry =
            new PriorityQueue<>((a, b) -> a.reservation().expiresAt().compareTo(b.reservation().expiresAt()));

    /** {@code true} if a low-stock alert was already sent and the product has not been "restocked". */
    private boolean lowStockAlerted;

    ProductStock(String sku, ProductCategory category, CategoryPolicy policy) {
        this.sku = sku;
        this.category = category;
        this.policy = policy;
    }

    String sku() {
        return sku;
    }

    ProductCategory category() {
        return category;
    }

    CategoryPolicy policy() {
        return policy;
    }

    int available() {
        return stock - reserved;
    }

    void addStock(int quantity) {
        try {
            stock = Math.addExact(stock, quantity);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Stock of " + sku + " would exceed the supported maximum", e);
        }
    }

    /** Holds the reservation's units. The caller has already validated that enough are available. */
    void hold(ReservationRecord record) {
        reserved += record.reservation().quantity();
        byExpiry.add(record);
    }

    /** Turns the reservation into a sale: it leaves "reserved" and the physical stock at once. */
    void confirm(ReservationRecord record) {
        int quantity = record.reservation().quantity();
        reserved -= quantity;
        stock -= quantity;
        record.setState(ReservationRecord.State.CONFIRMED);
        // The entry stays in the queue until its expiry; purgeExpired ignores it because it is not PENDING.
    }

    /** Releases PENDING reservations whose window has already elapsed at {@code now}. */
    void purgeExpired(Instant now) {
        ReservationRecord head;
        while ((head = byExpiry.peek()) != null && !now.isBefore(head.reservation().expiresAt())) {
            byExpiry.poll();
            if (head.state() == ReservationRecord.State.PENDING) {
                head.setState(ReservationRecord.State.EXPIRED);
                reserved -= head.reservation().quantity();
            }
        }
    }

    // ---- Low-stock alert (without repeats) ----

    /**
     * Decides whether to alert: available at or below the threshold AND no alert sent yet.
     * Marks the alert as sent, so it returns {@code true} only ONCE per cycle.
     */
    boolean claimLowStockAlert(int threshold) {
        if (available() <= threshold && !lowStockAlerted) {
            lowStockAlerted = true;
            return true;
        }
        return false;
    }

    /** The product is above the threshold again (e.g. a reservation expired): re-arms the alert. */
    void rearmAlertIfAbove(int threshold) {
        if (available() > threshold) {
            lowStockAlerted = false;
        }
    }

    /** Re-arms the alert unconditionally (restock, or a failure while delivering the alert). */
    void rearmAlert() {
        lowStockAlerted = false;
    }
}
