package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NEW. The app automatically resends an order if the connection is slow: a retry must NOT hold
 * units twice (the customer would take stock they did not ask for).
 */
class IdempotencyTest {

    private MutableClock clock;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        service = Inventory.create(clock, (sku, available) -> { });
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.registerProduct("SKU-2", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
        service.addStock("SKU-2", 10);
    }

    @Test
    void retriedReserveReturnsSameReservationAndHoldsUnitsOnce() {
        Reservation first = service.reserve("ORDER-1", "SKU-1", 3);
        clock.advance(Duration.ofMinutes(1));
        Reservation retry = service.reserve("ORDER-1", "SKU-1", 3);

        assertEquals(first, retry, "a retry must not extend the window or change the reservation");
        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void retriedReserveAfterConfirmationDoesNotReserveAgain() {
        Reservation first = service.reserve("ORDER-1", "SKU-1", 3);
        service.confirm("ORDER-1");

        assertEquals(first, service.reserve("ORDER-1", "SKU-1", 3));
        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void retriedConfirmIsANoOp() {
        service.reserve("ORDER-1", "SKU-1", 3);
        service.confirm("ORDER-1");
        service.confirm("ORDER-1");
        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void sameOrderIdWithDifferentDataIsRejected() {
        service.reserve("ORDER-1", "SKU-1", 3);
        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-1", 4));
        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-2", 3));
        assertEquals(7, service.available("SKU-1"));
        assertEquals(10, service.available("SKU-2"));
    }

    @Test
    void retryAfterExpirationCreatesAFreshReservation() {
        service.reserve("ORDER-1", "SKU-1", 3);
        clock.advance(Duration.ofMinutes(20));

        Reservation fresh = service.reserve("ORDER-1", "SKU-1", 3);
        assertEquals(clock.instant().plus(Duration.ofMinutes(15)), fresh.expiresAt());
        assertEquals(7, service.available("SKU-1"));
        service.confirm("ORDER-1");
    }
}
