package dev.stockflow.warehouse.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.stockflow.warehouse.AbstractWarehouseIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Connection pool exhaustion through the HTTP layer: a pool of 5 connections and a 1 s wait for a free one, against
 * 20 requests that each hold a connection for 600 ms. The first requests get a connection, later ones wait, and the
 * ones still waiting after 1 s must fail fast with a 503, not hang.
 */
@SpringBootTest(properties = {
        "stockflow.lab.unsafe-stock=true",
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.connection-timeout=1000"
})
@AutoConfigureMockMvc
class ConnectionPoolTest extends AbstractWarehouseIntegrationTest {

    private record Reply(int status, long millis, String body) {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meters;

    private Reply hold(int millis) throws Exception {
        long started = System.nanoTime();
        var response = mockMvc.perform(get("/api/v1/stock/lab/hold").param("millis", String.valueOf(millis)))
                .andReturn().getResponse();
        return new Reply(response.getStatus(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                response.getContentAsString());
    }

    @Test
    @DisplayName("20 requests holding a connection for 600 ms on a pool of 5: late ones get a 503 after about 1 s")
    void exhaustedPoolFailsFastWith503() throws Exception {
        int requests = 20;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Reply>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return hold(600);
                }));
            }
            go.countDown();
            double maxPending = 0;
            while (futures.stream().anyMatch(f -> !f.isDone())) {
                maxPending = Math.max(maxPending, meters.get("hikaricp.connections.pending").gauge().value());
                Thread.sleep(20);
            }
            List<Reply> replies = new ArrayList<>();
            for (Future<Reply> future : futures) {
                replies.add(future.get(30, TimeUnit.SECONDS));
            }

            long ok = replies.stream().filter(r -> r.status() == 200).count();
            List<Reply> busy = replies.stream().filter(r -> r.status() == 503).toList();
            System.out.println("pool of 5, 20 requests: " + ok + " x 200, " + busy.size() + " x 503, max pending "
                    + maxPending + ", 503 after " + busy.stream().mapToLong(Reply::millis).min().orElse(0) + " to "
                    + busy.stream().mapToLong(Reply::millis).max().orElse(0) + " ms");
            assertThat(ok + busy.size()).as("only 200 and 503 occur").isEqualTo(requests);
            assertThat(ok).isBetween(5L, 15L);
            assertThat(busy).isNotEmpty().allSatisfy(r -> {
                assertThat(r.millis()).isBetween(900L, 3_000L);
                assertThat(r.body()).contains("Database busy");
            });
            assertThat(maxPending).as("requests were waiting for a connection").isGreaterThan(0);

            // HikariPool builds its metrics PoolStats with a 1 s refresh (checked in HikariCP 7.0.2), so the gauge
            // can still show the busy connections up to 1 s after they were returned; wait for it, do not assert at once
            long released = System.nanoTime();
            await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(10))
                    .until(() -> meters.get("hikaricp.connections.active").gauge().value() == 0);
            System.out.println("active connections back to 0 "
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - released) + " ms after the last reply");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("After the burst the pool is free again and a normal request succeeds")
    void poolRecoversAfterTheBurst() throws Exception {
        exhaustedPoolFailsFastWith503();

        assertThat(meters.get("hikaricp.connections.active").gauge().value()).isZero();
        mockMvc.perform(get("/api/v1/stock/lab/hold").param("millis", "0")).andExpect(status().isOk());
    }
}
