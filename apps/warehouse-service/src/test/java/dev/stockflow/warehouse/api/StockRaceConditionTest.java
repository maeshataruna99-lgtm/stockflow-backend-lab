package dev.stockflow.warehouse.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stockflow.warehouse.AbstractWarehouseIntegrationTest;
import dev.stockflow.warehouse.api.dto.StockLevelRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Race condition lab on the real PostgreSQL container: many threads withdraw from one stock row at the same
 * moment. The invariant after any number of concurrent withdrawals: units taken + units left = units at the start,
 * and nothing is taken that was not there.
 */
class StockRaceConditionTest extends AbstractWarehouseIntegrationTest {

    static final int THREADS = 16;

    /** How the concurrent requests ended. */
    record Outcome(int succeeded, int insufficient) {
    }

    @Autowired
    private StockService stockService;

    private long newStock(int quantity) {
        return stockService.create(new StockLevelRequest(1L, System.nanoTime(), quantity)).id();
    }

    private Outcome withdrawConcurrently(long stockId, int attempts, int quantity) throws Exception {
        return withdrawConcurrently(stockId, attempts, quantity, stockService::withdraw);
    }

    private Outcome withdrawConcurrently(long stockId, int attempts, int quantity,
                                         BiConsumer<Long, Integer> withdrawal) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                Callable<Boolean> attempt = () -> {
                    go.await();
                    try {
                        withdrawal.accept(stockId, quantity);
                        return true;
                    } catch (InsufficientStockException e) {
                        return false;
                    }
                };
                futures.add(pool.submit(attempt));
            }
            go.countDown();
            int succeeded = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(60, TimeUnit.SECONDS)) {
                    succeeded++;
                }
            }
            return new Outcome(succeeded, attempts - succeeded);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("The unsafe read-modify-write loses updates: units taken plus units left exceed the start")
    void unsafeWithdrawLosesUpdates() throws Exception {
        boolean seen = false;
        for (int round = 1; round <= 10 && !seen; round++) {
            long id = newStock(100);

            Outcome outcome = withdrawConcurrently(id, 100, 1, stockService::withdrawUnsafe);

            int left = stockService.get(id).quantity();
            System.out.println("unsafe round " + round + ": " + outcome.succeeded() + " withdrawals succeeded, "
                    + left + " units left, start 100");
            seen = outcome.succeeded() + left != 100;
        }
        // the race is probabilistic, so up to 10 rounds; one round with 100 simultaneous requests is almost always enough
        assertThat(seen).as("a lost update was observed").isTrue();
    }

    @Test
    @DisplayName("100 concurrent withdrawals of 1 from a stock of 50 take exactly 50 and refuse 50")
    void neverTakesMoreThanInStock() throws Exception {
        long id = newStock(50);

        Outcome outcome = withdrawConcurrently(id, 100, 1);

        assertThat(outcome.succeeded()).isEqualTo(50);
        assertThat(outcome.insufficient()).isEqualTo(50);
        assertThat(stockService.get(id).quantity()).isZero();
    }

    @Test
    @DisplayName("Units taken plus units left always equal the starting stock")
    void takenPlusLeftEqualsStart() throws Exception {
        long id = newStock(100);

        Outcome outcome = withdrawConcurrently(id, 40, 3);

        int left = stockService.get(id).quantity();
        assertThat(outcome.succeeded() * 3 + left).isEqualTo(100);
        assertThat(outcome.succeeded()).isEqualTo(33);
        assertThat(left).isEqualTo(1);
    }
}
