package dev.stockflow.warehouse.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.stockflow.warehouse.AbstractWarehouseIntegrationTest;
import dev.stockflow.warehouse.api.OutboxService;
import dev.stockflow.warehouse.api.OutboxService.OutboxEvent;
import dev.stockflow.warehouse.api.StockService;
import dev.stockflow.warehouse.api.dto.StockLevelRequest;
import dev.stockflow.warehouse.api.dto.StockLevelResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Eventual consistency and CQRS read-model projection with a measurable convergence condition (Phase 9, unit 9.5:
 * topics 42 and 45).
 *
 * <p>The write model ({@code stock_levels}) records each location's stock and emits outbox events; the query model
 * ({@code cqrs_product_stock_view}) pre-aggregates total units per product across locations. Before the projector runs
 * the read model is stale and {@code isConverged(productId)} returns {@code false}; once all outbox events up to the
 * latest write have been projected, {@code isConverged(productId)} is {@code true} and the read model matches the sum
 * of the write model.
 */
class CqrsProjectionTest extends AbstractWarehouseIntegrationTest {

    @Autowired
    private StockService stockService;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void setUpReadModelTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS cqrs_product_stock_view ("
                + "product_id BIGINT PRIMARY KEY, "
                + "total_quantity INT NOT NULL, "
                + "last_outbox_id BIGINT NOT NULL)");
        jdbc.update("DELETE FROM cqrs_product_stock_view");
        jdbc.update("DELETE FROM stock_outbox");
        jdbc.update("DELETE FROM processed_events");
    }

    /**
     * Measurable convergence condition: the read model has processed up to the highest outbox sequence id that exists
     * for the product's stock rows, and no unpublished outbox rows remain for them.
     */
    private boolean isConverged(long productId) {
        Long maxOutboxId = jdbc.queryForObject(
                "SELECT coalesce(max(o.id), 0) FROM stock_outbox o "
                        + "JOIN stock_levels s ON s.id = o.aggregate_id WHERE s.product_id = ?",
                Long.class, productId);
        Integer unpublished = jdbc.queryForObject(
                "SELECT count(*) FROM stock_outbox o "
                        + "JOIN stock_levels s ON s.id = o.aggregate_id "
                        + "WHERE s.product_id = ? AND o.published_at IS NULL",
                Integer.class, productId);
        Long viewLastId = jdbc.query(
                "SELECT last_outbox_id FROM cqrs_product_stock_view WHERE product_id = ?",
                (rs, rowNum) -> rs.getLong(1), productId).stream().findFirst().orElse(0L);
        return unpublished != null && unpublished == 0 && maxOutboxId != null && maxOutboxId.equals(viewLastId);
    }

    private int queryReadModelTotal(long productId) {
        return jdbc.query(
                "SELECT total_quantity FROM cqrs_product_stock_view WHERE product_id = ?",
                (rs, rowNum) -> rs.getInt(1), productId).stream().findFirst().orElse(0);
    }

    private int queryWriteModelSum(long productId) {
        Integer sum = jdbc.queryForObject(
                "SELECT coalesce(sum(quantity), 0) FROM stock_levels WHERE product_id = ?",
                Integer.class, productId);
        return sum != null ? sum : 0;
    }

    private void applyEventToReadModel(OutboxEvent event) {
        outboxService.consumeOnce(event.eventId(), event.eventType(), () -> {
            try {
                JsonNode json = mapper.readTree(event.payload());
                long stockId = json.has("stockId") ? json.get("stockId").asLong() : event.aggregateId();
                Long productId = jdbc.queryForObject(
                        "SELECT product_id FROM stock_levels WHERE id = ?", Long.class, stockId);
                int delta = switch (event.eventType()) {
                    case "STOCK_CREATED" -> json.get("quantity").asInt();
                    case "STOCK_WITHDRAWN" -> -json.get("withdrawn").asInt();
                    case "STOCK_REPLENISHED" -> json.get("added").asInt();
                    default -> 0;
                };
                jdbc.update("INSERT INTO cqrs_product_stock_view (product_id, total_quantity, last_outbox_id) "
                                + "VALUES (?, ?, ?) "
                                + "ON CONFLICT (product_id) DO UPDATE SET "
                                + "total_quantity = cqrs_product_stock_view.total_quantity + EXCLUDED.total_quantity, "
                                + "last_outbox_id = greatest(cqrs_product_stock_view.last_outbox_id, EXCLUDED.last_outbox_id)",
                        productId, delta, event.id());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to project event " + event.eventId(), e);
            }
        });
    }

    @Test
    @DisplayName("Read model lags behind the write model during the inconsistency window, then converges to the exact sum once projected")
    void readModelLagsDuringInconsistencyWindowAndConvergesAfterProjection() {
        long productId = System.nanoTime();
        StockLevelResponse loc1 = stockService.create(new StockLevelRequest(1L, productId, 10));
        stockService.create(new StockLevelRequest(2L, productId, 15));
        stockService.withdraw(loc1.id(), 4);

        // 1. Before projector runs: write model has 21, read model has 0, convergence is false
        assertThat(queryWriteModelSum(productId)).isEqualTo(21);
        assertThat(queryReadModelTotal(productId)).as("stale CQRS read model before projection").isZero();
        assertThat(isConverged(productId)).isFalse();

        // 2. Projector relays and applies all pending outbox events
        long started = System.nanoTime();
        List<OutboxEvent> relayedEvents = new ArrayList<>();
        outboxService.relayBatch(20, event -> {
            relayedEvents.add(event);
            applyEventToReadModel(event);
        });
        long convergenceMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started);
        System.out.println("CQRS read model converged after " + relayedEvents.size() + " events in " + convergenceMicros + " us");

        assertThat(isConverged(productId)).isTrue();
        assertThat(queryReadModelTotal(productId)).isEqualTo(21);

        // 3. Replaying the already-projected events is a no-op
        relayedEvents.forEach(this::applyEventToReadModel);
        assertThat(queryReadModelTotal(productId)).isEqualTo(21);
    }
}
