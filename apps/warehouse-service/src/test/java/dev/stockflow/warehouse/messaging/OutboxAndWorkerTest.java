package dev.stockflow.warehouse.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stockflow.warehouse.AbstractWarehouseIntegrationTest;
import dev.stockflow.warehouse.api.InsufficientStockException;
import dev.stockflow.warehouse.api.OutboxService;
import dev.stockflow.warehouse.api.OutboxService.OutboxEvent;
import dev.stockflow.warehouse.api.StockService;
import dev.stockflow.warehouse.api.dto.StockLevelRequest;
import dev.stockflow.warehouse.api.dto.StockLevelResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Transactional outbox, scheduled/concurrent background relay workers, and idempotent consumer inbox (Phase 9,
 * unit 9.1: topics 37, 38, 39, 44).
 */
class OutboxAndWorkerTest extends AbstractWarehouseIntegrationTest {

    @Autowired
    private StockService stockService;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanOutboxTables() {
        jdbc.update("DELETE FROM stock_outbox");
        jdbc.update("DELETE FROM processed_events");
    }

    @Test
    @DisplayName("A stock write and its outbox row commit in the same local database transaction")
    void stockWriteAndOutboxRecordCommitInOneLocalTransaction() {
        StockLevelResponse created = stockService.create(new StockLevelRequest(1L, System.nanoTime(), 10));
        stockService.withdraw(created.id(), 4);

        List<String> types = jdbc.queryForList(
                "SELECT event_type FROM stock_outbox WHERE aggregate_id = ? ORDER BY id",
                String.class, created.id());
        assertThat(types).containsExactly("STOCK_CREATED", "STOCK_WITHDRAWN");
        assertThat(outboxService.unpublishedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("A failed stock write rolls back and leaves no phantom outbox record")
    void failedStockWriteRollsBackWithoutLeavingOutboxRecord() {
        StockLevelResponse created = stockService.create(new StockLevelRequest(1L, System.nanoTime(), 5));

        assertThatThrownBy(() -> stockService.withdraw(created.id(), 9))
                .isInstanceOf(InsufficientStockException.class);

        List<String> types = jdbc.queryForList(
                "SELECT event_type FROM stock_outbox WHERE aggregate_id = ? ORDER BY id",
                String.class, created.id());
        assertThat(types).containsExactly("STOCK_CREATED");
        assertThat(stockService.get(created.id()).quantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("Concurrent background workers polling with FOR UPDATE SKIP LOCKED relay each outbox row once")
    void relayPublishesPendingEventsAndConcurrentWorkersSkipLockedRows() throws Exception {
        for (int i = 0; i < 12; i++) {
            stockService.create(new StockLevelRequest(1L, System.nanoTime() + i, 10));
        }
        assertThat(outboxService.unpublishedCount()).isEqualTo(12);

        List<String> publishedIds = Collections.synchronizedList(new ArrayList<>());
        int workers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        for (int w = 0; w < workers; w++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    while (outboxService.relayBatch(3, event -> publishedIds.add(event.eventId())) > 0) {
                        // keep draining until no unpublished rows remain
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        assertThat(publishedIds).hasSize(12);
        assertThat(Set.copyOf(publishedIds)).hasSize(12);
        assertThat(outboxService.unpublishedCount()).isZero();
    }

    @Test
    @DisplayName("Duplicate delivery from an at-least-once outbox publisher is applied once by the idempotent consumer")
    void duplicateDeliveryFromRelayCrashIsAppliedOnceByIdempotentConsumer() {
        StockLevelResponse created = stockService.create(new StockLevelRequest(1L, System.nanoTime(), 10));
        List<OutboxEvent> deliveredToBroker = new ArrayList<>();

        // Simulate a publisher that delivers the message to the broker and then crashes before committing published_at
        assertThatThrownBy(() -> outboxService.relayBatch(10, event -> {
            deliveredToBroker.add(event);
            throw new IllegalStateException("broker ack lost before DB commit");
        })).isInstanceOf(IllegalStateException.class);

        // Because published_at rolled back, the next relay poll delivers the same event a second time
        int relayed = outboxService.relayBatch(10, deliveredToBroker::add);
        assertThat(relayed).isEqualTo(1);
        assertThat(deliveredToBroker).hasSize(2);
        assertThat(deliveredToBroker.get(0).eventId()).isEqualTo(deliveredToBroker.get(1).eventId());

        // The idempotent consumer receives both deliveries and applies the side effect once
        AtomicInteger sideEffectRuns = new AtomicInteger();
        Set<Long> projectedAggregates = ConcurrentHashMap.newKeySet();
        boolean firstApplied = outboxService.consumeOnce(
                deliveredToBroker.get(0).eventId(), deliveredToBroker.get(0).eventType(), () -> {
                    sideEffectRuns.incrementAndGet();
                    projectedAggregates.add(created.id());
                });
        boolean secondApplied = outboxService.consumeOnce(
                deliveredToBroker.get(1).eventId(), deliveredToBroker.get(1).eventType(),
                sideEffectRuns::incrementAndGet);

        assertThat(firstApplied).isTrue();
        assertThat(secondApplied).isFalse();
        assertThat(sideEffectRuns.get()).isEqualTo(1);
        assertThat(projectedAggregates).containsExactly(created.id());
    }

    @Test
    @DisplayName("Scheduled outbox purge deletes old published rows and keeps unpublished and recent rows")
    void scheduledPurgeDeletesOnlyOldPublishedOutboxRows() {
        StockLevelResponse first = stockService.create(new StockLevelRequest(1L, System.nanoTime(), 10));
        StockLevelResponse second = stockService.create(new StockLevelRequest(1L, System.nanoTime() + 1, 10));
        StockLevelResponse third = stockService.create(new StockLevelRequest(1L, System.nanoTime() + 2, 10));

        // Publish first and second; leave third unpublished
        jdbc.update("UPDATE stock_outbox SET published_at = now() - interval '2 days' WHERE aggregate_id = ?", first.id());
        jdbc.update("UPDATE stock_outbox SET published_at = now() - interval '1 hour' WHERE aggregate_id = ?", second.id());

        int purged = outboxService.purgePublishedOlderThan(Duration.ofHours(24));
        assertThat(purged).isEqualTo(1);

        List<Long> remaining = jdbc.queryForList("SELECT aggregate_id FROM stock_outbox ORDER BY id", Long.class);
        assertThat(remaining).containsExactly(second.id(), third.id());
    }
}
