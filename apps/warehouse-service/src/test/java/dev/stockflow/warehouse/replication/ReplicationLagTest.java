package dev.stockflow.warehouse.replication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Read replica lab on real infrastructure: a PostgreSQL primary and a hot standby that streams WAL from it, two
 * containers on one network, set up with the same scripts as the compose profile "replica"
 * (labs/database/replica). Shows the replica is read-only, how long a committed write takes to become visible there,
 * and what a read from a replica that is behind returns, with the read-your-writes check that makes a stale read
 * detectable (wait for the replica to reach the LSN of the write, else read the primary).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReplicationLagTest {

    private static Network network;
    private static PostgreSQLContainer<?> primaryContainer;
    private static GenericContainer<?> replicaContainer;
    private static JdbcTemplate primary;
    private static JdbcTemplate replica;

    private static MountableFile script(String name) {
        Path path = Path.of("..", "..", "labs", "database", "replica", name).toAbsolutePath().normalize();
        assertThat(Files.exists(path)).as("lab script " + path).isTrue();
        return MountableFile.forHostPath(path, 0755);
    }

    private static JdbcTemplate connect(String host, int port) {
        return new JdbcTemplate(new SingleConnectionDataSource("jdbc:postgresql://" + host + ":" + port + "/test",
                "test", "test", true)); // one open connection: polling must not pay for a new connection each time
    }

    @BeforeAll
    static void startPrimaryAndReplica() {
        network = Network.newNetwork();
        primaryContainer = new PostgreSQLContainer<>("postgres:16.15-alpine")
                .withNetwork(network).withNetworkAliases("pg-primary")
                .withCopyFileToContainer(script("01-replication.sh"), "/docker-entrypoint-initdb.d/01-replication.sh");
        primaryContainer.start();
        replicaContainer = new GenericContainer<>("postgres:16.15-alpine")
                .withNetwork(network).withNetworkAliases("pg-replica")
                .withEnv("PRIMARY_HOST", "pg-primary")
                .withEnv("REPLICATION_PASSWORD", primaryContainer.getPassword())
                .withCopyFileToContainer(script("replica-entrypoint.sh"), "/replica-entrypoint.sh")
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh", "/replica-entrypoint.sh"))
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept read-only connections.*\\n", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        replicaContainer.start();
        primary = connect(primaryContainer.getHost(), primaryContainer.getMappedPort(5432));
        replica = connect(replicaContainer.getHost(), replicaContainer.getMappedPort(5432));
        primary.execute("CREATE TABLE lab_lag (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, marker TEXT NOT NULL)");
        // the table reaches the replica through replication too: a test that runs first must not see it missing (SQLSTATE 42P01
        // instead of the 25006 it expects; this happened on a CI run)
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(5)).until(() ->
                Boolean.TRUE.equals(replica.queryForObject("SELECT to_regclass('lab_lag') IS NOT NULL", Boolean.class)));
    }

    @AfterAll
    static void stop() {
        if (replicaContainer != null) {
            replicaContainer.stop();
        }
        if (primaryContainer != null) {
            primaryContainer.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    private static int count(JdbcTemplate db, String marker) {
        return db.queryForObject("SELECT count(*) FROM lab_lag WHERE marker = ?", Integer.class, marker);
    }

    /** Read-your-writes check: has the replica replayed up to the given WAL position within the timeout? */
    private static boolean replicaReached(String lsn, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(replica.queryForObject("SELECT pg_last_wal_replay_lsn() >= ?::pg_lsn", Boolean.class, lsn))) {
                return true;
            }
            Thread.sleep(5);
        }
        return false;
    }

    /** Commits one row on the primary and returns the milliseconds until the replica shows it. */
    private static long visibleOnReplicaAfter(String marker) {
        primary.update("INSERT INTO lab_lag (marker) VALUES (?)", marker);
        long committed = System.nanoTime();
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(1)).until(() -> count(replica, marker) == 1);
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - committed);
    }

    @Test
    @Order(1)
    @DisplayName("The standby streams from the primary and knows it is a replica")
    void replicaStreamsFromThePrimary() {
        assertThat(replica.queryForObject("SELECT pg_is_in_recovery()", Boolean.class)).isTrue();
        assertThat(primary.queryForObject("SELECT pg_is_in_recovery()", Boolean.class)).isFalse();

        await().atMost(Duration.ofSeconds(15)).until(() -> primary.queryForObject(
                "SELECT count(*) FROM pg_stat_replication WHERE state = 'streaming' AND application_name = 'lab_replica'",
                Integer.class) == 1);
    }

    @Test
    @Order(2)
    @DisplayName("A write on the replica is refused: it is read-only (SQLSTATE 25006)")
    void replicaRefusesWrites() {
        assertThatThrownBy(() -> replica.update("INSERT INTO lab_lag (marker) VALUES ('on the replica')"))
                .satisfies(e -> {
                    Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
                    assertThat(((SQLException) root).getSQLState()).isEqualTo("25006");
                });
        assertThat(count(primary, "on the replica")).isZero();
    }

    @Test
    @Order(3)
    @DisplayName("A committed write becomes visible on the replica after a short delay")
    void writeBecomesVisibleOnTheReplica() {
        long[] millis = new long[5];
        for (int i = 0; i < millis.length; i++) {
            millis[i] = visibleOnReplicaAfter("visible-" + i);
        }
        System.out.println("time until a committed row is visible on the replica (ms, 5 writes): "
                + java.util.Arrays.toString(millis));
        assertThat(java.util.Arrays.stream(millis).max().orElseThrow()).isLessThan(5_000);
    }

    @Test
    @Order(4)
    @DisplayName("A replica that is behind returns stale reads; waiting for the write's LSN detects it and the primary answers")
    void staleReadWhileTheReplicaIsBehind() throws Exception {
        replica.execute("SELECT pg_wal_replay_pause()"); // the replica receives WAL but stops applying it
        await().atMost(Duration.ofSeconds(5)).until(
                () -> "paused".equals(replica.queryForObject("SELECT pg_get_wal_replay_pause_state()", String.class)));
        try {
            primary.update("INSERT INTO lab_lag (marker) VALUES ('written while behind')");
            String lsn = primary.queryForObject("SELECT pg_current_wal_lsn()::text", String.class);

            Long lagBytes = primary.queryForObject(
                    "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn)::bigint FROM pg_stat_replication "
                            + "WHERE application_name = 'lab_replica'", Long.class);
            System.out.println("replica paused: primary has the row (" + count(primary, "written while behind")
                    + "), replica returns " + count(replica, "written while behind") + ", replay lag " + lagBytes + " bytes");
            assertThat(count(primary, "written while behind")).isEqualTo(1);
            assertThat(count(replica, "written while behind")).as("stale read from the replica").isZero();
            assertThat(lagBytes).isPositive();

            assertThat(replicaReached(lsn, 500)).as("the replica has not reached the write's LSN").isFalse();
            // so a caller that needs its own write falls back to the primary
            assertThat(count(primary, "written while behind")).isEqualTo(1);

            replica.execute("SELECT pg_wal_replay_resume()");
            long resumed = System.nanoTime();
            assertThat(replicaReached(lsn, 10_000)).isTrue();
            System.out.println("after resume the replica reached the LSN in "
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - resumed) + " ms");
            assertThat(count(replica, "written while behind")).isEqualTo(1);
        } finally {
            replica.execute("SELECT pg_wal_replay_resume()");
        }
    }
}
