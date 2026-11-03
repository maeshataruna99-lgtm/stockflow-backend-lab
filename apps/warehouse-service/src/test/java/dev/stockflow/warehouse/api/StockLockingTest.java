package dev.stockflow.warehouse.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stockflow.warehouse.AbstractWarehouseIntegrationTest;
import dev.stockflow.warehouse.api.dto.StockLevelRequest;
import dev.stockflow.warehouse.repository.StockLevelRepository;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Locking lab on the real PostgreSQL container: the read-modify-write that lost updates in StockRaceConditionTest,
 * made safe in two ways. Optimistic: write only if the row still has the version that was read, otherwise re-read and
 * try again, a bounded number of times. Pessimistic: SELECT ... FOR UPDATE, so the row is locked from the read to
 * the commit and a second withdrawal waits.
 */
class StockLockingTest extends AbstractWarehouseIntegrationTest {

    static final int THREADS = 16;

    /** How the concurrent requests ended. */
    record Outcome(int succeeded, int insufficient, int conflicts) {
    }

    @Autowired
    private StockService stockService;

    @Autowired
    private StockLevelRepository stockLevelRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry meters;

    private long newStock(int quantity) {
        return stockService.create(new StockLevelRequest(1L, System.nanoTime(), quantity)).id();
    }

    private double optimisticRetries() {
        return meters.counter("warehouse.stock.retries", "lock", "optimistic").count();
    }

    private Outcome withdrawConcurrently(long stockId, int attempts, int quantity,
                                         BiConsumer<Long, Integer> withdrawal) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                Callable<String> attempt = () -> {
                    go.await();
                    try {
                        withdrawal.accept(stockId, quantity);
                        return "ok";
                    } catch (InsufficientStockException e) {
                        return "insufficient";
                    } catch (ConcurrentUpdateException e) {
                        return "conflict";
                    }
                };
                futures.add(pool.submit(attempt));
            }
            go.countDown();
            int ok = 0;
            int insufficient = 0;
            int conflicts = 0;
            for (Future<String> future : futures) {
                switch (future.get(120, TimeUnit.SECONDS)) {
                    case "ok" -> ok++;
                    case "insufficient" -> insufficient++;
                    default -> conflicts++;
                }
            }
            return new Outcome(ok, insufficient, conflicts);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Optimistic: no update is lost, every accepted unit is gone from the stock, conflicts are retried")
    void optimisticKeepsTheInvariant() throws Exception {
        long id = newStock(100);
        double retriesBefore = optimisticRetries();

        Outcome outcome = withdrawConcurrently(id, 100, 1, stockService::withdrawOptimistic);

        int left = stockService.get(id).quantity();
        System.out.println("optimistic: accepted=" + outcome.succeeded() + " gave up after retries="
                + outcome.conflicts() + " left=" + left + " retries=" + (optimisticRetries() - retriesBefore));
        assertThat(outcome.succeeded() + left).isEqualTo(100);
        assertThat(outcome.succeeded() + outcome.conflicts()).isEqualTo(100);
        assertThat(optimisticRetries() - retriesBefore).isGreaterThan(0);
    }

    @Test
    @DisplayName("Optimistic: with demand above the stock it never takes more than is there")
    void optimisticNeverOversells() throws Exception {
        long id = newStock(50);

        Outcome outcome = withdrawConcurrently(id, 100, 1, stockService::withdrawOptimistic);

        int left = stockService.get(id).quantity();
        assertThat(outcome.succeeded()).isLessThanOrEqualTo(50);
        assertThat(outcome.succeeded() + left).isEqualTo(50);
    }

    @Test
    @DisplayName("Pessimistic: 100 simultaneous withdrawals from a stock of 100 are all accepted and leave 0")
    void pessimisticSerializesWithdrawals() throws Exception {
        long id = newStock(100);

        Outcome outcome = withdrawConcurrently(id, 100, 1, stockService::withdrawPessimistic);

        assertThat(outcome.succeeded()).isEqualTo(100);
        assertThat(stockService.get(id).quantity()).isZero();
    }

    @Test
    @DisplayName("Pessimistic: with demand above the stock it accepts exactly the stock and refuses the rest")
    void pessimisticRefusesWhenEmpty() throws Exception {
        long id = newStock(50);

        Outcome outcome = withdrawConcurrently(id, 100, 1, stockService::withdrawPessimistic);

        assertThat(outcome.succeeded()).isEqualTo(50);
        assertThat(outcome.insufficient()).isEqualTo(50);
        assertThat(stockService.get(id).quantity()).isZero();
    }

    @Test
    @DisplayName("Pessimistic: a withdrawal that cannot get the row lock gives up after the lock timeout, it does not hang")
    void pessimisticLockWaitIsBounded() throws Exception {
        long id = newStock(10);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<?> holding = holder.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                stockLevelRepository.findByIdForUpdate(id);
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            assertThatThrownBy(() -> stockService.withdrawPessimistic(id, 1))
                    .isInstanceOf(PessimisticLockingFailureException.class);
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            System.out.println("pessimistic lock wait gave up after " + waitedMillis + " ms");
            assertThat(waitedMillis).isBetween(1500L, 10_000L);
            release.countDown();
            holding.get(10, TimeUnit.SECONDS);
            assertThat(stockService.get(id).quantity()).isEqualTo(10);
        } finally {
            release.countDown();
            holder.shutdownNow();
        }
    }
}
