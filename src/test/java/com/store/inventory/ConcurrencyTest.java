package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * NEW. "Hundreds of customers buying the same thing at once": there must never be overselling.
 * Threads start together via a latch to maximize contention.
 */
class ConcurrencyTest {

    private static final int THREADS = 64;

    @Test
    void neverOversellsUnderContention() throws Exception {
        List<String> alerts = Collections.synchronizedList(new ArrayList<>());
        InventoryService service = Inventory.create(Clock.systemUTC(), (sku, n) -> alerts.add(sku));
        service.registerProduct("HOT", ProductCategory.STANDARD);
        service.addStock("HOT", 50);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        runConcurrently(THREADS * 4, i -> {
            try {
                service.reserve("ORDER-" + i, "HOT", 1); // 256 orders for 50 units
                succeeded.incrementAndGet();
            } catch (InsufficientStockException e) {
                rejected.incrementAndGet();
            }
        });

        assertEquals(50, succeeded.get(), "exactly the available stock gets reserved");
        assertEquals(THREADS * 4 - 50, rejected.get());
        assertEquals(0, service.available("HOT"));
        assertEquals(1, alerts.size(), "a single alert even if many threads cross the threshold at once");
    }

    @Test
    void concurrentRetriesOfTheSameOrderHoldUnitsOnlyOnce() throws Exception {
        InventoryService service = Inventory.create(Clock.systemUTC(), (sku, n) -> { });
        service.registerProduct("SKU", ProductCategory.STANDARD);
        service.addStock("SKU", 100);

        runConcurrently(THREADS, i -> service.reserve("ORDER-SAME", "SKU", 3)); // same order, 64 retries

        assertEquals(97, service.available("SKU"));
    }

    @Test
    void concurrentConfirmsSellUnitsOnlyOnce() throws Exception {
        InventoryService service = Inventory.create(Clock.systemUTC(), (sku, n) -> { });
        service.registerProduct("SKU", ProductCategory.STANDARD);
        service.addStock("SKU", 10);
        service.reserve("ORDER-1", "SKU", 4);

        runConcurrently(THREADS, i -> service.confirm("ORDER-1"));

        assertEquals(6, service.available("SKU"));
    }

    // ---- helpers ----

    private interface Task {
        void run(int index) throws Exception;
    }

    private static void runConcurrently(int count, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                start.await();
                task.run(index);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS); // propagates any unexpected exception
        }
        pool.shutdownNow();
    }
}
