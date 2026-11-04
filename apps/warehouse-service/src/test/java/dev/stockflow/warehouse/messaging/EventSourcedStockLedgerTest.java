package dev.stockflow.warehouse.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Isolated educational lab for Event Sourcing (Phase 9, unit 9.6: topic 46).
 *
 * <p>This is an isolated experiment on its own schema and is NOT the persistence model of {@code warehouse-service}
 * (which persists current state in {@code stock_levels}). Here state is never overwritten: every change appends an
 * immutable fact to {@code es_stock_events} guarded by {@code UNIQUE (stream_id, version)}, current or historical
 * balances are reconstructed by replaying the stream, and snapshots avoid replaying from version 1 on long streams.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventSourcedStockLedgerTest {

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;

    record SnapshotAndTail(int quantity, int version, int eventsRead) {
    }

    @BeforeAll
    static void startDatabase() {
        postgres = new PostgreSQLContainer<>("postgres:16.15-alpine");
        postgres.start();
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), true));
        jdbc.execute("CREATE TABLE es_stock_events ("
                + "seq BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, "
                + "stream_id BIGINT NOT NULL, "
                + "version INT NOT NULL, "
                + "event_type VARCHAR(40) NOT NULL, "
                + "delta INT NOT NULL, "
                + "reason TEXT NOT NULL, "
                + "created_at TIMESTAMPTZ NOT NULL DEFAULT now(), "
                + "UNIQUE (stream_id, version))");
        jdbc.execute("CREATE TABLE es_stock_snapshots ("
                + "stream_id BIGINT PRIMARY KEY, "
                + "version INT NOT NULL, "
                + "quantity INT NOT NULL)");
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static void appendEvent(long streamId, int expectedVersion, String eventType, int delta, String reason) {
        SnapshotAndTail current = replayFromStart(streamId, Integer.MAX_VALUE);
        if (current.version() != expectedVersion) {
            jdbc.update("INSERT INTO es_stock_events (stream_id, version, event_type, delta, reason) VALUES (?, ?, ?, ?, ?)",
                    streamId, expectedVersion + 1, eventType, delta, reason);
            return;
        }
        if (current.quantity() + delta < 0) {
            throw new IllegalStateException("Insufficient stock in stream " + streamId + ": balance " + current.quantity());
        }
        jdbc.update("INSERT INTO es_stock_events (stream_id, version, event_type, delta, reason) VALUES (?, ?, ?, ?, ?)",
                streamId, expectedVersion + 1, eventType, delta, reason);
    }

    private static SnapshotAndTail replayFromStart(long streamId, int upToVersion) {
        AtomicInteger qty = new AtomicInteger();
        AtomicInteger ver = new AtomicInteger();
        AtomicInteger count = new AtomicInteger();
        jdbc.query("SELECT version, delta FROM es_stock_events WHERE stream_id = ? AND version <= ? ORDER BY version",
                rs -> {
                    qty.addAndGet(rs.getInt("delta"));
                    ver.set(rs.getInt("version"));
                    count.incrementAndGet();
                }, streamId, upToVersion);
        return new SnapshotAndTail(qty.get(), ver.get(), count.get());
    }

    private static void saveSnapshot(long streamId) {
        SnapshotAndTail state = replayFromStart(streamId, Integer.MAX_VALUE);
        jdbc.update("INSERT INTO es_stock_snapshots (stream_id, version, quantity) VALUES (?, ?, ?) "
                        + "ON CONFLICT (stream_id) DO UPDATE SET version = EXCLUDED.version, quantity = EXCLUDED.quantity",
                streamId, state.version(), state.quantity());
    }

    private static SnapshotAndTail replayFromSnapshot(long streamId) {
        var snap = jdbc.query("SELECT version, quantity FROM es_stock_snapshots WHERE stream_id = ?",
                (rs, rowNum) -> new int[]{rs.getInt("version"), rs.getInt("quantity")}, streamId)
                .stream().findFirst().orElse(new int[]{0, 0});
        AtomicInteger qty = new AtomicInteger(snap[1]);
        AtomicInteger ver = new AtomicInteger(snap[0]);
        AtomicInteger tailRead = new AtomicInteger();
        jdbc.query("SELECT version, delta FROM es_stock_events WHERE stream_id = ? AND version > ? ORDER BY version",
                rs -> {
                    qty.addAndGet(rs.getInt("delta"));
                    ver.set(rs.getInt("version"));
                    tailRead.incrementAndGet();
                }, streamId, snap[0]);
        return new SnapshotAndTail(qty.get(), ver.get(), tailRead.get());
    }

    @Test
    @Order(1)
    @DisplayName("State is reconstructed by replaying immutable events and supports point-in-time historical queries")
    void stateIsReconstructedByReplayingEventsAndSupportsPointInTimeQueries() {
        long stream = 500L;
        appendEvent(stream, 0, "STOCK_OPENED", 20, "Initial inventory count");
        appendEvent(stream, 1, "UNITS_RECEIVED", 10, "Purchase order PO-11");
        appendEvent(stream, 2, "UNITS_WITHDRAWN", -7, "Order ORD-90");
        appendEvent(stream, 3, "UNITS_DAMAGED", -2, "Broken crate inspection");

        assertThat(replayFromStart(stream, 1).quantity()).isEqualTo(20);
        assertThat(replayFromStart(stream, 2).quantity()).isEqualTo(30);
        assertThat(replayFromStart(stream, 3).quantity()).isEqualTo(23);
        assertThat(replayFromStart(stream, 4).quantity()).isEqualTo(21);

        // Domain invariant check against replayed state rejects an overdraft without appending an event
        assertThatThrownBy(() -> appendEvent(stream, 4, "UNITS_WITHDRAWN", -25, "Overdraft"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(replayFromStart(stream, Integer.MAX_VALUE).version()).isEqualTo(4);
    }

    @Test
    @Order(2)
    @DisplayName("Two concurrent commands appending at the same expected stream version fail with UNIQUE constraint 23505")
    void concurrentAppendAtSameExpectedVersionFailsWith23505() {
        long stream = 500L;
        // Both commands observed version 4; the first writes version 5, the second collides on (stream_id, version)
        appendEvent(stream, 4, "UNITS_RECEIVED", 5, "First writer wins version 5");

        assertThatThrownBy(() -> appendEvent(stream, 4, "UNITS_WITHDRAWN", -3, "Stale writer also tries version 5"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
                    assertThat(((SQLException) root).getSQLState()).isEqualTo("23505");
                });
    }

    @Test
    @Order(3)
    @DisplayName("Replaying from a snapshot plus tail events matches full replay from version 1 while reading only the tail")
    void snapshotPlusTailEventsEqualsFullReplayWithFewerRowsRead() {
        long stream = 500L;
        // Stream is at version 5 (balance 26); take a snapshot, then append 3 more events (versions 6, 7, 8)
        saveSnapshot(stream);
        appendEvent(stream, 5, "UNITS_WITHDRAWN", -4, "Order ORD-91");
        appendEvent(stream, 6, "UNITS_RECEIVED", 8, "Purchase order PO-12");
        appendEvent(stream, 7, "UNITS_WITHDRAWN", -1, "Sample");

        SnapshotAndTail full = replayFromStart(stream, Integer.MAX_VALUE);
        SnapshotAndTail fromSnapshot = replayFromSnapshot(stream);

        assertThat(fromSnapshot.quantity()).isEqualTo(full.quantity()).isEqualTo(29);
        assertThat(fromSnapshot.version()).isEqualTo(full.version()).isEqualTo(8);
        assertThat(full.eventsRead()).isEqualTo(8);
        assertThat(fromSnapshot.eventsRead()).as("only events after the v5 snapshot were read").isEqualTo(3);
    }
}
