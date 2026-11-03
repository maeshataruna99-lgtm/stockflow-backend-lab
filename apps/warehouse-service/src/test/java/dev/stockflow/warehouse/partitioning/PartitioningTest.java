package dev.stockflow.warehouse.partitioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
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
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Partitioning lab (unit 7.11), an isolated experiment on its own PostgreSQL container; nothing here is part of the
 * services' schema. A table of 1,200,000 events is range-partitioned by month on created_at (12 partitions and a
 * default one) and compared with an unpartitioned twin holding the same rows. What partitioning gives and what it costs.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PartitioningTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static PostgreSQLContainer<?> postgres;
    private static SingleConnectionDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startAndLoad() {
        postgres = new PostgreSQLContainer<>("postgres:16.15-alpine");
        postgres.start();
        dataSource = new SingleConnectionDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), true);
        jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("""
                CREATE TABLE lab_events (
                    id BIGINT GENERATED ALWAYS AS IDENTITY,
                    user_id INT NOT NULL, kind TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL, payload TEXT
                ) PARTITION BY RANGE (created_at)""");
        for (int month = 1; month <= 12; month++) {
            String from = "2026-%02d-01".formatted(month);
            String to = month == 12 ? "2027-01-01" : "2026-%02d-01".formatted(month + 1);
            jdbc.execute("CREATE TABLE lab_events_2026_%02d PARTITION OF lab_events FOR VALUES FROM ('%s') TO ('%s')"
                    .formatted(month, from, to));
        }
        jdbc.execute("CREATE TABLE lab_events_default PARTITION OF lab_events DEFAULT");
        jdbc.execute("CREATE INDEX ON lab_events (created_at)");
        jdbc.execute("""
                INSERT INTO lab_events (user_id, kind, created_at, payload)
                SELECT g % 1000, 'k' || (g % 7),
                       timestamptz '2026-01-01' + (g % 365) * interval '1 day' + (g % 86400) * interval '1 second',
                       md5(g::text)
                FROM generate_series(1, 1200000) g""");
        jdbc.execute("CREATE TABLE lab_events_flat (id BIGINT PRIMARY KEY, user_id INT NOT NULL, kind TEXT NOT NULL, "
                + "created_at TIMESTAMPTZ NOT NULL, payload TEXT)");
        jdbc.execute("INSERT INTO lab_events_flat SELECT * FROM lab_events");
        jdbc.execute("CREATE INDEX ON lab_events_flat (created_at)");
        jdbc.execute("ANALYZE");
    }

    @AfterAll
    static void stop() {
        dataSource.destroy();
        postgres.stop();
    }

    private static void relations(JsonNode node, java.util.Collection<String> out) {
        if (node.has("Relation Name")) {
            out.add(node.get("Relation Name").asText());
        }
        if (node.has("Plans")) {
            node.get("Plans").forEach(child -> relations(child, out));
        }
    }

    /** Names of the tables the executed plan read, and the median execution time of 5 runs after a warm-up. */
    private record Plan(java.util.Set<String> tables, double medianMillis) {
    }

    private static Plan plan(String sql) throws Exception {
        List<Double> times = new ArrayList<>();
        java.util.Set<String> tables = new TreeSet<>();
        for (int run = 0; run < 6; run++) {
            JsonNode root = JSON.readTree(jdbc.queryForObject("EXPLAIN (ANALYZE, FORMAT JSON) " + sql, String.class)).get(0);
            if (run > 0) {
                times.add(root.get("Execution Time").asDouble());
            }
            tables.clear();
            relations(root.get("Plan"), tables);
        }
        Collections.sort(times);
        return new Plan(tables, times.get(times.size() / 2));
    }

    private static String sqlState(Throwable failure) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(failure);
        return root instanceof SQLException sql ? sql.getSQLState() : null;
    }

    @Test
    @Order(1)
    @DisplayName("The same rows are in both tables, spread over the 12 monthly partitions")
    void dataIsLoaded() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lab_events", Integer.class)).isEqualTo(1_200_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lab_events_flat", Integer.class)).isEqualTo(1_200_000);
        List<Map<String, Object>> perPartition = jdbc.queryForList(
                "SELECT tableoid::regclass::text AS part, count(*) AS n FROM lab_events GROUP BY 1 ORDER BY 1");
        System.out.println("rows per partition: " + perPartition);
        assertThat(perPartition).hasSize(12);
    }

    @Test
    @Order(2)
    @DisplayName("A filter on the partition key reads one partition; a filter on another column reads all of them")
    void pruningNeedsThePartitionKey() throws Exception {
        String day = "created_at >= '2026-03-10' AND created_at < '2026-03-11'";
        Plan partitionedDay = plan("SELECT count(*) FROM lab_events WHERE " + day);
        Plan flatDay = plan("SELECT count(*) FROM lab_events_flat WHERE " + day);
        Plan partitionedOther = plan("SELECT count(*) FROM lab_events WHERE user_id = 5 AND kind = 'k3'");
        Plan flatOther = plan("SELECT count(*) FROM lab_events_flat WHERE user_id = 5 AND kind = 'k3'");

        System.out.printf("one day:        partitioned %.2f ms reading %s | flat %.2f ms reading %s%n",
                partitionedDay.medianMillis(), partitionedDay.tables(), flatDay.medianMillis(), flatDay.tables());
        System.out.printf("user_id + kind: partitioned %.2f ms reading %d tables | flat %.2f ms%n",
                partitionedOther.medianMillis(), partitionedOther.tables().size(), flatOther.medianMillis());
        assertThat(partitionedDay.tables()).as("partitions read for a one-day filter").hasSize(1)
                .allMatch(t -> t.startsWith("lab_events_2026_03"));
        assertThat(partitionedOther.tables()).as("partitions read without a filter on the key").hasSizeGreaterThanOrEqualTo(12);
    }

    @Test
    @Order(3)
    @DisplayName("Dropping a partition removes a month of data far faster than deleting its rows")
    void retentionByDroppingAPartition() {
        long deleteStart = System.nanoTime();
        int deleted = jdbc.update("DELETE FROM lab_events_flat WHERE created_at >= '2026-01-01' AND created_at < '2026-02-01'");
        long deleteMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - deleteStart);

        long dropStart = System.nanoTime();
        jdbc.execute("ALTER TABLE lab_events DETACH PARTITION lab_events_2026_01");
        jdbc.execute("DROP TABLE lab_events_2026_01");
        long dropMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - dropStart);

        System.out.println("remove January (" + deleted + " rows): DELETE " + deleteMillis + " ms, DETACH + DROP PARTITION "
                + dropMillis + " ms");
        assertThat(deleted).isGreaterThan(90_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lab_events", Integer.class)).isEqualTo(1_200_000 - deleted);
        assertThat(dropMillis).isLessThan(deleteMillis);
    }

    @Test
    @Order(4)
    @DisplayName("A primary key on a partitioned table must contain the partition key")
    void primaryKeyMustIncludeThePartitionKey() {
        assertThatThrownBy(() -> jdbc.execute("ALTER TABLE lab_events ADD PRIMARY KEY (id)"))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("0A000"));
        jdbc.execute("ALTER TABLE lab_events ADD PRIMARY KEY (id, created_at)");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE tablename = 'lab_events' AND indexdef LIKE '%UNIQUE%'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @Order(5)
    @DisplayName("A row outside every range is refused without a default partition, and lands in the default one with it")
    void rowsOutsideEveryRange() {
        jdbc.execute("CREATE TABLE lab_strict (created_at TIMESTAMPTZ NOT NULL) PARTITION BY RANGE (created_at)");
        jdbc.execute("CREATE TABLE lab_strict_2026 PARTITION OF lab_strict FOR VALUES FROM ('2026-01-01') TO ('2027-01-01')");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO lab_strict VALUES ('2030-05-05')"))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));

        jdbc.update("INSERT INTO lab_events (user_id, kind, created_at) VALUES (1, 'late', '2030-05-05')");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lab_events_default", Integer.class)).isEqualTo(1);
    }

    @Test
    @Order(6)
    @DisplayName("Hash partitioning spreads rows evenly over the partitions")
    void hashPartitionsAreEven() {
        jdbc.execute("CREATE TABLE lab_hash (user_id INT NOT NULL) PARTITION BY HASH (user_id)");
        for (int remainder = 0; remainder < 4; remainder++) {
            jdbc.execute("CREATE TABLE lab_hash_%d PARTITION OF lab_hash FOR VALUES WITH (MODULUS 4, REMAINDER %d)"
                    .formatted(remainder, remainder));
        }
        jdbc.execute("INSERT INTO lab_hash SELECT g FROM generate_series(1, 100000) g");

        List<Integer> counts = jdbc.queryForList("SELECT count(*)::int FROM lab_hash GROUP BY tableoid ORDER BY 1", Integer.class);
        System.out.println("100,000 user ids over 4 hash partitions: " + counts);
        assertThat(counts).hasSize(4).allSatisfy(n -> assertThat(n).isBetween(22_500, 27_500));
    }
}
