package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.StockAlertListener;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** NEW. Several alert channels (email today, more tomorrow). */
class CompositeStockAlertListenerTest {

    @Test
    void deliversToEveryChannel() {
        List<String> seen = new ArrayList<>();
        var composite = new CompositeStockAlertListener(List.of(
                (sku, n) -> seen.add("email:" + sku + ":" + n),
                (sku, n) -> seen.add("slack:" + sku + ":" + n)));
        composite.onLowStock("SKU", 3);
        assertEquals(List.of("email:SKU:3", "slack:SKU:3"), seen);
    }

    @Test
    void oneFailingChannelDoesNotBlockTheOthers() {
        List<String> seen = new ArrayList<>();
        StockAlertListener broken = (sku, n) -> { throw new IllegalStateException("down"); };
        var composite = new CompositeStockAlertListener(List.of(broken, (sku, n) -> seen.add(sku)));
        composite.onLowStock("SKU", 3);
        assertEquals(List.of("SKU"), seen);
    }

    @Test
    void failsOnlyWhenAllChannelsFail() {
        StockAlertListener broken = (sku, n) -> { throw new IllegalStateException("down"); };
        var composite = new CompositeStockAlertListener(List.of(broken, broken));
        assertThrows(IllegalStateException.class, () -> composite.onLowStock("SKU", 3));
    }

    @Test
    void requiresAtLeastOneChannel() {
        assertThrows(IllegalArgumentException.class, () -> new CompositeStockAlertListener(List.of()));
    }
}
