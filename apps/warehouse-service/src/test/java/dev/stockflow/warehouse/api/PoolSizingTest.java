package dev.stockflow.warehouse.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.stockflow.warehouse.AbstractWarehouseIntegrationTest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pool size against throughput on the real PostgreSQL container. The same fixed batch of queries is run by 32
 * client threads through pools of 1, 2, 4, 8 and 16 connections. Two kinds of query: one that only waits inside the
 * database (pg_sleep, uses no CPU) and one that burns CPU. The printed table is the measurement; the assertions only
 * hold what must be true on any machine.
 */
class PoolSizingTest extends AbstractWarehouseIntegrationTest {

    private static final int CLIENT_THREADS = 32;
    private static final int[] POOL_SIZES = {1, 2, 4, 8, 16};

    @Autowired
    private DataSource dataSource;

    private long millisFor(int poolSize, String sql, int queries) throws Exception {
        HikariDataSource source = dataSource.unwrap(HikariDataSource.class);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(source.getJdbcUrl());
        config.setUsername(source.getUsername());
        config.setPassword(source.getPassword());
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(poolSize);
        config.setConnectionTimeout(120_000);
        try (HikariDataSource pool = new HikariDataSource(config)) {
            runQuery(pool, sql); // opens the connections before the clock starts
            ExecutorService clients = Executors.newFixedThreadPool(CLIENT_THREADS);
            try {
                long started = System.nanoTime();
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < queries; i++) {
                    futures.add(clients.submit(() -> {
                        runQuery(pool, sql);
                        return null;
                    }));
                }
                for (Future<?> future : futures) {
                    future.get(120, TimeUnit.SECONDS);
                }
                return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            } finally {
                clients.shutdownNow();
            }
        }
    }

    private static void runQuery(DataSource pool, String sql) throws Exception {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
        }
    }

    private long[] table(String label, String sql, int queries) throws Exception {
        long[] millis = new long[POOL_SIZES.length];
        StringBuilder line = new StringBuilder(label + " (" + queries + " queries, " + CLIENT_THREADS + " client threads):");
        for (int i = 0; i < POOL_SIZES.length; i++) {
            millis[i] = millisFor(POOL_SIZES[i], sql, queries);
            line.append("  pool ").append(POOL_SIZES[i]).append(" = ").append(millis[i]).append(" ms");
        }
        System.out.println(line);
        return millis;
    }

    @Test
    @DisplayName("Queries that wait in the database: more connections give nearly proportional throughput")
    void waitingQueriesScaleWithThePool() throws Exception {
        long[] millis = table("pg_sleep 50 ms", "SELECT pg_sleep(0.05)::text", 160);

        assertThat(millis[0]).as("pool 1 against pool 8").isGreaterThan(millis[3] * 3);
    }

    @Test
    @DisplayName("CPU-bound queries: throughput stops growing once the pool passes what the database CPU can run")
    void cpuQueriesFlattenOutWithThePool() throws Exception {
        long[] millis = table("CPU 500k-row series", "SELECT count(*) FROM generate_series(1, 500000)", 40);

        assertThat(millis[0]).as("pool 1 is slower than pool 16").isGreaterThan(millis[4]);
        System.out.println("CPU cores visible to the JVM: " + Runtime.getRuntime().availableProcessors());
    }
}
