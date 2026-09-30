package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NEW. README rules: expiration per category, per-order limit and confirmation.
 * All time is controlled with {@link MutableClock}.
 */
class ReservationRulesTest {

    private MutableClock clock;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        service = Inventory.create(clock, (sku, available) -> { });
        service.registerProduct("STD", ProductCategory.STANDARD);
        service.registerProduct("PRE", ProductCategory.PRE_ORDER);
        service.registerProduct("FLASH", ProductCategory.FLASH_SALE);
        service.addStock("STD", 100);
        service.addStock("PRE", 100);
        service.addStock("FLASH", 100);
    }

    // ---- Time to pay per category (the boundary is tested: 1 second before / exactly at expiry) ----

    @Test
    void standardExpiresAfter15Minutes() {
        assertExpiresAfter("STD", Duration.ofMinutes(15));
    }

    @Test
    void preOrderExpiresAfter24Hours() {
        assertExpiresAfter("PRE", Duration.ofHours(24));
    }

    @Test
    void flashSaleExpiresAfter5Minutes() {
        assertExpiresAfter("FLASH", Duration.ofMinutes(5));
    }

    private void assertExpiresAfter(String sku, Duration window) {
        Reservation r = service.reserve("ORDER-1", sku, 2);
        assertEquals(clock.instant().plus(window), r.expiresAt());
        assertEquals(98, service.available(sku));

        clock.advance(window.minusSeconds(1));
        assertEquals(98, service.available(sku), "still reserved 1 second before expiry");

        clock.advance(Duration.ofSeconds(1));
        assertEquals(100, service.available(sku), "units are released when the window elapses");
    }

    @Test
    void expiredUnitsCanBeReservedByAnotherCustomer() {
        service.registerProduct("LAST", ProductCategory.STANDARD);
        service.addStock("LAST", 1);
        service.reserve("ORDER-1", "LAST", 1);
        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-2", "LAST", 1));

        clock.advance(Duration.ofMinutes(15));
        service.reserve("ORDER-2", "LAST", 1); // no longer throws
        assertEquals(0, service.available("LAST"));
    }

    @Test
    void cannotConfirmAnExpiredReservation() {
        service.reserve("ORDER-1", "STD", 2);
        clock.advance(Duration.ofMinutes(15));
        assertThrows(IllegalStateException.class, () -> service.confirm("ORDER-1"));
        assertEquals(100, service.available("STD"));
    }

    @Test
    void confirmedReservationNeverReturnsToStock() {
        service.reserve("ORDER-1", "STD", 2);
        service.confirm("ORDER-1");
        clock.advance(Duration.ofDays(2));
        assertEquals(98, service.available("STD"));
    }

    @Test
    void confirmWithoutReservationFails() {
        assertThrows(IllegalStateException.class, () -> service.confirm("NOPE"));
    }

    // ---- Per-order limit ----

    @Test
    void flashSaleAllowsUpTo2UnitsPerOrder() {
        service.reserve("ORDER-1", "FLASH", 2);
        assertEquals(98, service.available("FLASH"));
    }

    @Test
    void flashSaleRejectsMoreThan2UnitsPerOrderEvenIfStockIsEnough() {
        assertThrows(OrderLimitExceededException.class, () -> service.reserve("ORDER-1", "FLASH", 3));
        assertEquals(100, service.available("FLASH"), "a rejected order holds nothing");
    }

    @Test
    void standardAndPreOrderHaveNoLimit() {
        service.reserve("ORDER-1", "STD", 50);
        service.reserve("ORDER-2", "PRE", 50);
        assertEquals(50, service.available("STD"));
        assertEquals(50, service.available("PRE"));
    }

    // ---- Contract validations ----

    @Test
    void rejectsNonPositiveQuantities() {
        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "STD", 0));
        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "STD", -1));
        assertThrows(IllegalArgumentException.class, () -> service.addStock("STD", 0));
        assertThrows(IllegalArgumentException.class, () -> service.addStock("STD", -5));
    }

    @Test
    void addStockToUnregisteredProductFails() {
        assertThrows(IllegalArgumentException.class, () -> service.addStock("UNKNOWN", 1));
    }

    @Test
    void unknownProductHasNoUnits() {
        assertEquals(0, service.available("UNKNOWN"));
        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-1", "UNKNOWN", 1));
    }

    @Test
    void reRegisteringWithSameCategoryIsHarmlessButChangingItIsRejected() {
        service.registerProduct("STD", ProductCategory.STANDARD);
        assertEquals(100, service.available("STD"));
        assertThrows(IllegalArgumentException.class, () -> service.registerProduct("STD", ProductCategory.FLASH_SALE));
    }
}
