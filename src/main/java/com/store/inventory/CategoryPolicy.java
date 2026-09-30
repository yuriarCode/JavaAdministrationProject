package com.store.inventory;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Business rules of a product category.
 *
 * NEW: these rules did not exist in the code before. They are modeled as data (instead of
 * {@code switch} statements scattered through the service) so that changing a rule means editing a
 * single table in {@link CategoryPolicies}.
 *
 * @param paymentWindow    time the customer has to pay before the reservation expires
 * @param maxUnitsPerOrder maximum units per order; empty = no limit
 */
record CategoryPolicy(Duration paymentWindow, OptionalInt maxUnitsPerOrder) {

    CategoryPolicy {
        Objects.requireNonNull(paymentWindow, "paymentWindow");
        Objects.requireNonNull(maxUnitsPerOrder, "maxUnitsPerOrder");
        if (paymentWindow.isZero() || paymentWindow.isNegative()) {
            throw new IllegalArgumentException("paymentWindow must be positive: " + paymentWindow);
        }
        if (maxUnitsPerOrder.isPresent() && maxUnitsPerOrder.getAsInt() <= 0) {
            throw new IllegalArgumentException("maxUnitsPerOrder must be positive");
        }
    }

    /** Policy with no per-order unit limit. */
    static CategoryPolicy unlimited(Duration paymentWindow) {
        return new CategoryPolicy(paymentWindow, OptionalInt.empty());
    }

    /** Policy with a maximum number of units per order. */
    static CategoryPolicy limited(Duration paymentWindow, int maxUnitsPerOrder) {
        return new CategoryPolicy(paymentWindow, OptionalInt.of(maxUnitsPerOrder));
    }

    /** @return {@code true} if {@code quantity} exceeds this category's per-order limit. */
    boolean exceedsOrderLimit(int quantity) {
        return maxUnitsPerOrder.isPresent() && quantity > maxUnitsPerOrder.getAsInt();
    }
}
