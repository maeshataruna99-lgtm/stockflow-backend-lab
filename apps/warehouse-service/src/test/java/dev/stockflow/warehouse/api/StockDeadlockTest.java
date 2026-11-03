package dev.stockflow.warehouse.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stockflow.warehouse.AbstractWarehouseIntegrationTest;
import dev.stockflow.warehouse.api.dto.StockLevelRequest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;

/**
 * Deadlock lab on the real PostgreSQL container. A transfer moves units between two stock rows and so needs two row
 * locks. Two transfers in opposite directions that lock "from" first and "to" second each hold one lock and wait for
 * the other's: a deadlock. PostgreSQL notices after deadlock_timeout (1 s by default), rolls one transaction back
 * (SQLSTATE 40P01) and lets the other finish. The fix is to take the locks in one global order, here ascending id.
 */
class StockDeadlockTest extends AbstractWarehouseIntegrationTest {

    @Autowired
    private StockService stockService;

    private record Pair(long a, long b) {
    }

    /** Two rows of one product at two locations. */
    private Pair newPair(int quantity) {
        long product = System.nanoTime();
        return new Pair(stockService.create(new StockLevelRequest(1L, product, quantity)).id(),
                stockService.create(new StockLevelRequest(2L, product, quantity)).id());
    }

    private int quantity(long id) {
        return stockService.get(id).quantity();
    }

    private static Throwable runAfter(CountDownLatch go, Runnable action) throws InterruptedException {
        go.await();
        try {
            action.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    @Test
    @DisplayName("Opposite unordered transfers deadlock: PostgreSQL rolls one back within seconds, the other commits")
    void oppositeTransfersDeadlockAndOneIsRolledBack() throws Exception {
        Pair rows = newPair(10);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> forward = pool.submit(() -> runAfter(go, () -> stockService.transferUnordered(rows.a(), rows.b(), 1, 400)));
            Future<Throwable> backward = pool.submit(() -> runAfter(go, () -> stockService.transferUnordered(rows.b(), rows.a(), 1, 400)));
            long started = System.nanoTime();
            go.countDown();
            Throwable forwardResult = forward.get(15, TimeUnit.SECONDS);
            Throwable backwardResult = backward.get(15, TimeUnit.SECONDS);
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            Throwable loser = forwardResult != null ? forwardResult : backwardResult;
            System.out.println("deadlock resolved in " + millis + " ms, loser: " + loser);
            assertThat(forwardResult == null ^ backwardResult == null).as("exactly one transfer failed").isTrue();
            Throwable root = NestedExceptionUtils.getMostSpecificCause(loser);
            assertThat(root).isInstanceOf(SQLException.class);
            assertThat(((SQLException) root).getSQLState()).as("deadlock_detected").isEqualTo("40P01");
            assertThat(millis).isLessThan(10_000);

            // the loser rolled back completely: only the winner's single unit moved
            boolean forwardWon = forwardResult == null;
            assertThat(quantity(rows.a())).isEqualTo(forwardWon ? 9 : 11);
            assertThat(quantity(rows.b())).isEqualTo(forwardWon ? 11 : 9);

            // recovery: the same transfer, retried in lock order, goes through
            if (forwardWon) {
                stockService.transfer(rows.b(), rows.a(), 1);
            } else {
                stockService.transfer(rows.a(), rows.b(), 1);
            }
            assertThat(quantity(rows.a())).isEqualTo(10);
            assertThat(quantity(rows.b())).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Transfers that lock rows in id order never deadlock, whatever the direction")
    void orderedTransfersNeverDeadlock() throws Exception {
        Pair rows = newPair(100);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Throwable>> results = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                boolean forward = i % 2 == 0;
                results.add(pool.submit(() -> runAfter(go, () -> stockService.transfer(
                        forward ? rows.a() : rows.b(), forward ? rows.b() : rows.a(), 1))));
            }
            go.countDown();
            for (Future<Throwable> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isNull();
            }
            assertThat(quantity(rows.a())).isEqualTo(100);
            assertThat(quantity(rows.b())).isEqualTo(100);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("A refused transfer (not enough stock) changes neither row")
    void refusedTransferChangesNothing() {
        Pair rows = newPair(5);

        assertThatThrownBy(() -> stockService.transfer(rows.a(), rows.b(), 6))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(quantity(rows.a())).isEqualTo(5);
        assertThat(quantity(rows.b())).isEqualTo(5);
    }

    @Test
    @DisplayName("A transfer to or from a missing row is a 404 and changes nothing")
    void missingRowIsNotFound() {
        Pair rows = newPair(5);

        assertThatThrownBy(() -> stockService.transfer(rows.a(), 987654L, 1))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(quantity(rows.a())).isEqualTo(5);
    }
}
