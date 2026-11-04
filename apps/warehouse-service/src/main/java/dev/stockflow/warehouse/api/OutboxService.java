package dev.stockflow.warehouse.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional outbox and idempotent consumer inbox (Phase 9, unit 9.1).
 *
 * <p>{@link #record} runs inside the caller's database transaction ({@code MANDATORY}), so a stock write and its
 * outbox row commit or roll back together. {@link #relayBatch} claims unpublished rows with
 * {@code FOR UPDATE SKIP LOCKED} so concurrent workers never pick the same row, publishes each event, and marks
 * {@code published_at = now()}. Because the broker publish is not in the database transaction, a crash between
 * publishing and committing {@code published_at} delivers the same {@code event_id} again (at-least-once delivery);
 * {@link #consumeOnce} deduplicates by inserting {@code event_id} into {@code processed_events} with
 * {@code ON CONFLICT DO NOTHING} in the same transaction as the consumer's side effect.
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    public record OutboxEvent(long id, String eventId, long aggregateId, String eventType, String payload) {
    }

    @FunctionalInterface
    public interface EventPublisher {
        void publish(OutboxEvent event);
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final StockProperties properties;

    OutboxService(JdbcTemplate jdbc, ObjectMapper mapper, StockProperties properties) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String record(long aggregateId, String eventType, Map<String, Object> payload) {
        String eventId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO stock_outbox (event_id, aggregate_id, event_type, payload) VALUES (?, ?, ?, ?)",
                eventId, aggregateId, eventType, toJson(new TreeMap<>(payload)));
        return eventId;
    }

    /**
     * Claims up to {@code batchSize} unpublished rows with {@code FOR UPDATE SKIP LOCKED}, publishes them in id order,
     * and marks them published. Two workers calling this concurrently skip each other's locked rows.
     */
    @Transactional
    public int relayBatch(int batchSize, EventPublisher publisher) {
        List<OutboxEvent> batch = jdbc.query(
                "SELECT id, event_id, aggregate_id, event_type, payload FROM stock_outbox "
                        + "WHERE published_at IS NULL ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, rowNum) -> new OutboxEvent(
                        rs.getLong("id"),
                        rs.getString("event_id"),
                        rs.getLong("aggregate_id"),
                        rs.getString("event_type"),
                        rs.getString("payload")),
                batchSize);
        for (OutboxEvent event : batch) {
            publisher.publish(event);
            jdbc.update("UPDATE stock_outbox SET published_at = now() WHERE id = ?", event.id());
        }
        return batch.size();
    }

    /**
     * Idempotent consumer inbox: claims {@code eventId} in {@code processed_events} and runs {@code sideEffect} in the
     * same transaction. Returns {@code true} when the event was applied, {@code false} when it was a duplicate.
     */
    @Transactional
    public boolean consumeOnce(String eventId, String eventType, Runnable sideEffect) {
        int claimed = jdbc.update(
                "INSERT INTO processed_events (event_id, event_type) VALUES (?, ?) ON CONFLICT (event_id) DO NOTHING",
                eventId, eventType);
        if (claimed == 0) {
            return false;
        }
        sideEffect.run();
        return true;
    }

    @Transactional(readOnly = true)
    public int unpublishedCount() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM stock_outbox WHERE published_at IS NULL", Integer.class);
        return count != null ? count : 0;
    }

    @Transactional
    public int purgePublishedOlderThan(Duration retention) {
        return jdbc.update(
                "DELETE FROM stock_outbox WHERE published_at IS NOT NULL AND published_at < now() - (? * interval '1 second')",
                retention.toSeconds());
    }

    @Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT1H")
    void purgePublished() {
        int purged = purgePublishedOlderThan(properties.idempotencyRetention());
        if (purged > 0) {
            log.info("Purged {} published outbox rows older than {}", purged, properties.idempotencyRetention());
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize outbox payload", e);
        }
    }
}
