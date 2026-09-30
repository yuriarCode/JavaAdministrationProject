package com.store.inventory;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.StockAlertListener;
import java.time.Clock;

/**
 * Entry point used by our automated tests. Keep this signature exactly as it is,
 * and build your implementation here.
 *
 * MODIFIED: it used to throw UnsupportedOperationException("TODO"). The signature of
 * {@code create(Clock, StockAlertListener)} did NOT change (contract rule); only its body was implemented.
 */
public final class Inventory {

    private Inventory() {
    }

    public static InventoryService create(Clock clock, StockAlertListener alertListener) {
        // Built with the current business rules (CategoryPolicies.defaults()) and the threshold of
        // 5 units. For additional alert channels, see CompositeStockAlertListener.
        return new InMemoryInventoryService(
                clock,
                alertListener,
                CategoryPolicies.defaults(),
                InMemoryInventoryService.DEFAULT_LOW_STOCK_THRESHOLD);
    }
}
