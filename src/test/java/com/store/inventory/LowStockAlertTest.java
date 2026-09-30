package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.StockAlertListener;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** NEW. Purchasing alert: at 5 units or fewer, only once until the product is restocked. */
class LowStockAlertTest {

    private MutableClock clock;
    private final List<String> alerts = new ArrayList<>();
    private InventoryService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        service = Inventory.create(clock, (sku, available) -> alerts.add(sku + ":" + available));
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
    }

    @Test
    void alertsWhenExactly5UnitsRemain() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 5); // 5 left -> alerts (5 counts)
        assertEquals(List.of("SKU-1:5"), alerts);
    }

    @Test
    void noAlertAt6Available() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 4); // 6 left
        assertEquals(List.of(), alerts);
    }

    @Test
    void alertsOnlyOnceWhileNotRestocked() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 5); // 5 -> alert
        service.reserve("ORDER-2", "SKU-1", 2); // 3 -> NO repeat
        service.reserve("ORDER-3", "SKU-1", 3); // 0 -> NO repeat
        assertEquals(List.of("SKU-1:5"), alerts);
    }

    @Test
    void restockRearmsTheAlert() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 6);  // 4 -> alert
        service.addStock("SKU-1", 20);           // restocked: 24
        service.reserve("ORDER-2", "SKU-1", 20); // 4 -> new alert
        assertEquals(List.of("SKU-1:4", "SKU-1:4"), alerts);
    }

    @Test
    void releasedReservationsAboveThresholdRearmTheAlert() {
        service.addStock("SKU-1", 8);
        service.reserve("ORDER-1", "SKU-1", 4); // 4 -> alert
        clock.advance(Duration.ofMinutes(15));  // expires: 8 available
        service.reserve("ORDER-2", "SKU-1", 4); // 4 -> alert again
        assertEquals(List.of("SKU-1:4", "SKU-1:4"), alerts);
    }

    @Test
    void retriedReserveDoesNotDuplicateTheAlert() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 6);
        service.reserve("ORDER-1", "SKU-1", 6); // retry
        assertEquals(1, alerts.size());
    }

    @Test
    void rejectedReservationDoesNotAlert() {
        service.addStock("SKU-1", 3);
        assertThrows(RuntimeException.class, () -> service.reserve("ORDER-1", "SKU-1", 9));
        assertEquals(List.of(), alerts);
    }

    @Test
    void failingListenerDoesNotBreakTheReservationAndAlertIsRetried() {
        AtomicBoolean down = new AtomicBoolean(true);
        List<String> delivered = new ArrayList<>();
        StockAlertListener flaky = (sku, available) -> {
            if (down.get()) {
                throw new IllegalStateException("SMTP down");
            }
            delivered.add(sku + ":" + available);
        };
        InventoryService svc = Inventory.create(clock, flaky);
        svc.registerProduct("SKU-F", ProductCategory.STANDARD);
        svc.addStock("SKU-F", 10);

        svc.reserve("ORDER-1", "SKU-F", 6); // the alert fails, but the reservation is made
        assertEquals(4, svc.available("SKU-F"));

        down.set(false);
        svc.reserve("ORDER-2", "SKU-F", 1); // the pending alert is retried
        assertEquals(List.of("SKU-F:3"), delivered);
    }
}
