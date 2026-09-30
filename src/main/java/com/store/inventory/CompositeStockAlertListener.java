package com.store.inventory;

import com.store.inventory.api.StockAlertListener;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends each low-stock alert to several channels (email, Slack, SMS...).
 *
 * NEW. Purchasing wants to add channels in the future. {@code StockAlertListener} is a single
 * exit point, so instead of touching the service, whoever creates it builds the list of channels:
 * {@code
 * Inventory.create(clock, new CompositeStockAlertListener(List.of(email, slack)));
 * }</pre>
 * A broken channel does not prevent the others from receiving the alert. The error is propagated
 * only if ALL of them fail, so the service re-arms the alert and retries it.
 */
public final class CompositeStockAlertListener implements StockAlertListener {

    private static final Logger LOG = Logger.getLogger(CompositeStockAlertListener.class.getName());

    private final List<StockAlertListener> delegates;

    public CompositeStockAlertListener(List<StockAlertListener> delegates) {
        if (delegates == null || delegates.isEmpty()) {
            throw new IllegalArgumentException("At least one alert channel is required");
        }
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public void onLowStock(String sku, int availableUnits) {
        RuntimeException failure = null;
        int failed = 0;
        for (StockAlertListener delegate : delegates) {
            try {
                delegate.onLowStock(sku, availableUnits);
            } catch (RuntimeException e) {
                failed++;
                LOG.log(Level.WARNING, "An alert channel failed for " + sku, e);
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failed == delegates.size()) {
            throw failure;
        }
    }
}
