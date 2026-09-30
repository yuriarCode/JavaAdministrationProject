package com.store.inventory;

import com.store.inventory.api.Reservation;
import java.time.Instant;

/**
 * Internal record of a reservation and its lifecycle.
 *
 * NEW. {@link Reservation} (from the contract) is an immutable record returned to the client;
 * this class adds the mutable state that the contract does not expose.
 *
 * The state is only modified while holding the lock of the owning {@link ProductStock}. It is
 * {@code volatile} because {@code reserve} on ANOTHER product may read it (a reused orderId)
 * without that lock.
 */
final class ReservationRecord {

    enum State { PENDING, CONFIRMED, EXPIRED }

    private final Reservation reservation;
    private final ProductStock product;
    private volatile State state = State.PENDING;

    ReservationRecord(Reservation reservation, ProductStock product) {
        this.reservation = reservation;
        this.product = product;
    }

    Reservation reservation() {
        return reservation;
    }

    ProductStock product() {
        return product;
    }

    State state() {
        return state;
    }

    void setState(State state) {
        this.state = state;
    }

    /**
     * Is the reservation still live at instant {@code now}? This is decided by time as well (not only
     * by state) because the EXPIRED state is assigned lazily, when the product is purged.
     * A reservation expires exactly at {@code expiresAt}: at that instant it is no longer live.
     */
    boolean isLiveAt(Instant now) {
        return switch (state) {
            case CONFIRMED -> true;
            case PENDING -> now.isBefore(reservation.expiresAt());
            case EXPIRED -> false;
        };
    }

    /** Is this the same request? Used to detect identical retries from the app. */
    boolean matches(String sku, int quantity) {
        return reservation.sku().equals(sku) && reservation.quantity() == quantity;
    }
}
