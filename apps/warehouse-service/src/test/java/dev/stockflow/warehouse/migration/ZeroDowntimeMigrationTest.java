package dev.stockflow.warehouse.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Zero-downtime migration lab on its own PostgreSQL container, with the service's real Flyway migrations. Renaming
 * warehouses.address to street_address is done in three steps (expand, migrate, contract) so that at every step the
 * release before and the release after can run against the same database at the same time. "Old app" and "new app"
 * below are the SQL each release sends. The last tests show what makes a migration itself a risk for a live system:
 * waiting for a lock stalls every later query on the table, and a plain CREATE INDEX blocks writers.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ZeroDowntimeMigrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer<>("postgres:16.15-alpine");
        postgres.start();
        flyway(4).migrate(); // the schema before this unit
        jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()));
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    private static Flyway flyway(int target) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target(String.valueOf(target)).load();
    }

    private static String sqlState(Throwable failure) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(failure);
        return root instanceof SQLException sql ? sql.getSQLState() : null;
    }

    // what the release before the change sends (column address)
    private static void oldInsert(String code, String address) {
        jdbc.update("INSERT INTO warehouses (code, name, address) VALUES (?, 'lab', ?)", code, address);
    }

    private static void oldUpdate(String code, String address) {
        jdbc.update("UPDATE warehouses SET address = ? WHERE code = ?", address, code);
    }

    private static String oldRead(String code) {
        return jdbc.queryForObject("SELECT address FROM warehouses WHERE code = ?", String.class, code);
    }

    // what the release after the change sends (column street_address)
    private static void newInsert(String code, String street) {
        jdbc.update("INSERT INTO warehouses (code, name, street_address) VALUES (?, 'lab', ?)", code, street);
    }

    private static void newUpdate(String code, String street) {
        jdbc.update("UPDATE warehouses SET street_address = ? WHERE code = ?", street, code);
    }

    private static String newRead(String code) {
        return jdbc.queryForObject("SELECT street_address FROM warehouses WHERE code = ?", String.class, code);
    }

    @Test
    @Order(1)
    @DisplayName("Before: only the old column exists and the new release cannot run yet")
    void before() {
        oldInsert("OLD-1", "Street 1");

        assertThat(oldRead("OLD-1")).isEqualTo("Street 1");
        assertThatThrownBy(() -> newRead("OLD-1")).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42703"));
    }

    @Test
    @Order(2)
    @DisplayName("Expand (V5): both columns exist and stay equal whichever release writes; the old release is unaffected")
    void expandKeepsBothReleasesWorking() {
        flyway(5).migrate();

        // existing rows were copied
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM warehouses WHERE street_address IS DISTINCT FROM address", Integer.class)).isZero();
        assertThat(newRead("OLD-1")).isEqualTo("Street 1");
        assertThat(newRead("WH-JKT01")).isEqualTo(oldRead("WH-JKT01")).isNotBlank();

        // the old release keeps writing only the old column; the new column follows
        oldInsert("OLD-2", "A2");
        assertThat(newRead("OLD-2")).isEqualTo("A2");
        oldUpdate("OLD-2", "A2b");
        assertThat(newRead("OLD-2")).isEqualTo("A2b");
        oldUpdate("OLD-2", null);
        assertThat(newRead("OLD-2")).isNull();

        // the new release writes only the new column; the old column follows
        newInsert("NEW-1", "S1");
        assertThat(oldRead("NEW-1")).isEqualTo("S1");
        newUpdate("NEW-1", "S1b");
        assertThat(oldRead("NEW-1")).isEqualTo("S1b");

        // both written differently in one statement: the new column is the source of truth
        jdbc.update("UPDATE warehouses SET address = 'old wins?', street_address = 'new wins' WHERE code = 'NEW-1'");
        assertThat(oldRead("NEW-1")).isEqualTo("new wins");
        assertThat(newRead("NEW-1")).isEqualTo("new wins");
        jdbc.update("INSERT INTO warehouses (code, name, address, street_address) VALUES ('BOTH-1', 'lab', 'a', 'b')");
        assertThat(oldRead("BOTH-1")).isEqualTo("b");
    }

    @Test
    @Order(3)
    @DisplayName("Contract (V6): the new release keeps working with the data intact; the old release's SQL now fails")
    void contractRemovesTheOldColumn() {
        String before = newRead("WH-JKT01");

        flyway(6).migrate();

        assertThat(newRead("WH-JKT01")).isEqualTo(before);
        assertThat(newRead("OLD-1")).isEqualTo("Street 1");
        newInsert("NEW-2", "S2");
        newUpdate("NEW-2", "S2b");
        assertThat(newRead("NEW-2")).isEqualTo("S2b");
        // this is why the contract step waits until no old release is running
        assertThatThrownBy(() -> oldRead("NEW-2")).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42703"));
        assertThatThrownBy(() -> oldInsert("OLD-3", "x")).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42703"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname = 'warehouses_sync_address'",
                Integer.class)).isZero();
    }

    /**
     * T1 holds a lock on the table (an open transaction that read it). A migration ALTER TABLE waits for it, and every
     * query that arrives after the ALTER queues behind the ALTER. Returns how long that later reader had to wait.
     */
    private long readerWaitWhileMigrationQueues(long lockTimeoutMillis, long holdMillis) throws Exception {
        jdbc.execute("CREATE TABLE IF NOT EXISTS lab_lock (id INT)");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection t1 = jdbc.getDataSource().getConnection()) {
            t1.setAutoCommit(false);
            try (Statement s = t1.createStatement()) {
                s.execute("SELECT count(*) FROM lab_lock"); // ACCESS SHARE until commit
            }
            String column = "c" + System.nanoTime();
            Future<String> migration = pool.submit(() -> {
                try (Connection c = jdbc.getDataSource().getConnection(); Statement s = c.createStatement()) {
                    if (lockTimeoutMillis > 0) {
                        s.execute("SET lock_timeout = " + lockTimeoutMillis);
                    }
                    s.execute("ALTER TABLE lab_lock ADD COLUMN " + column + " INT");
                    return "migrated";
                } catch (SQLException e) {
                    return e.getSQLState();
                }
            });
            Thread.sleep(200); // the ALTER is now queued behind T1
            Future<Long> reader = pool.submit(() -> {
                long started = System.nanoTime();
                jdbc.queryForObject("SELECT count(*) FROM lab_lock", Integer.class);
                return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            });
            Thread.sleep(Math.min(holdMillis, 300));
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                    + "AND (query LIKE 'ALTER TABLE lab_lock%' OR query LIKE 'SELECT count(*) FROM lab_lock%')", Integer.class);
            System.out.println("lock_timeout=" + lockTimeoutMillis + " ms: backends waiting on a lock: " + waiting);
            Thread.sleep(Math.max(0, holdMillis - 500));
            t1.commit();
            long waited = reader.get(30, TimeUnit.SECONDS);
            System.out.println("lock_timeout=" + (lockTimeoutMillis > 0 ? lockTimeoutMillis + " ms" : "none")
                    + ": migration " + migration.get(30, TimeUnit.SECONDS) + ", the later reader waited " + waited + " ms "
                    + "while T1 held its transaction for about " + holdMillis + " ms");
            return waited;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Order(4)
    @DisplayName("A migration that waits for a lock stalls later readers; lock_timeout caps that stall")
    void lockTimeoutBoundsTheStall() throws Exception {
        long withoutTimeout = readerWaitWhileMigrationQueues(0, 3000);
        long withTimeout = readerWaitWhileMigrationQueues(500, 3000);

        assertThat(withoutTimeout).as("reader waits for the whole hold when the ALTER may wait for ever")
                .isGreaterThan(2000);
        assertThat(withTimeout).as("reader is released when the ALTER gives up").isLessThan(1500);
    }

    @Test
    @Order(5)
    @Timeout(120)
    @DisplayName("CREATE INDEX CONCURRENTLY cannot run in a transaction, but Flyway can run it outside one")
    void concurrentIndexNeedsNoTransaction() throws Exception {
        jdbc.execute("CREATE TABLE lab_index (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, v INT NOT NULL)");
        jdbc.execute("INSERT INTO lab_index (v) SELECT g FROM generate_series(1, 100000) g");

        try (Connection c = jdbc.getDataSource().getConnection()) {
            c.setAutoCommit(false);
            assertThatThrownBy(() -> c.createStatement().execute("CREATE INDEX CONCURRENTLY lab_index_tx ON lab_index (v)"))
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("25001"));
            c.rollback();
        }

        Path dir = Files.createTempDirectory("flyway-concurrent");
        Files.writeString(dir.resolve("V100__concurrent_index.sql"),
                "CREATE INDEX CONCURRENTLY lab_index_v_idx ON lab_index (v);\n");
        Files.writeString(dir.resolve("V100__concurrent_index.sql.conf"), "executeInTransaction=false\n");
        // Flyway's default on PostgreSQL is a transactional advisory lock: its own connection stays "idle in
        // transaction" for the whole run, and CREATE INDEX CONCURRENTLY waits for every older transaction, so the run
        // never ends (observed: 8 minutes, pg_stat_activity showed the index build waiting on a virtualxid). Hence false.
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:" + dir).baselineOnMigrate(true).baselineVersion("99")
                .table("lab_flyway_history")
                .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
                .load().migrate();

        assertThat(jdbc.queryForObject("SELECT indisvalid FROM pg_index WHERE indexrelid = 'lab_index_v_idx'::regclass",
                Boolean.class)).isTrue();
    }

    /** Longest single insert while the given index statement runs on a table of 1,000,000 rows. */
    private long worstInsertDuringBuild(String indexStatement) throws Exception {
        AtomicBoolean building = new AtomicBoolean(true);
        AtomicLong worst = new AtomicLong();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        // about 200 inserts a second: a steady trickle of writes, not a flood that a concurrent build could never catch up with
        Future<?> writer = pool.submit(() -> {
            while (building.get()) {
                long started = System.nanoTime();
                jdbc.update("INSERT INTO lab_big (v) VALUES (1)");
                worst.accumulateAndGet(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), Math::max);
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        try {
            Thread.sleep(200);
            long started = System.nanoTime();
            jdbc.execute(indexStatement);
            System.out.println("index statement took " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    + " ms: " + indexStatement);
        } finally {
            building.set(false);
            writer.get(30, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
        return worst.get();
    }

    @Test
    @Order(6)
    @Timeout(240)
    @DisplayName("A plain CREATE INDEX blocks writers for the whole build; CONCURRENTLY does not")
    void plainIndexBlocksWriters() throws Exception {
        jdbc.execute("CREATE TABLE lab_big (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, v INT NOT NULL)");
        jdbc.execute("INSERT INTO lab_big (v) SELECT g FROM generate_series(1, 1000000) g");
        jdbc.execute("ANALYZE lab_big");

        long plain = worstInsertDuringBuild("CREATE INDEX lab_big_plain_idx ON lab_big (v)");
        long concurrent = worstInsertDuringBuild("CREATE INDEX CONCURRENTLY lab_big_conc_idx ON lab_big (v DESC)");

        System.out.println("slowest insert during the build: plain CREATE INDEX " + plain + " ms, CONCURRENTLY "
                + concurrent + " ms");
        assertThat(plain).isGreaterThan(concurrent);
    }
}
