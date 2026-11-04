package dev.stockflow.warehouse.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.stockflow.warehouse.AbstractWarehouseIntegrationTest;
import dev.stockflow.warehouse.TestTokens;
import dev.stockflow.warehouse.api.dto.StockLevelRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Idempotency keys on the stock writes (withdraw, transfer): a client that is unsure whether its request arrived may
 * send it again with the same Idempotency-Key and gets the first result back instead of a second withdrawal. The same
 * key with a different request is refused, because the client would otherwise believe it got what it asked for.
 */
@AutoConfigureMockMvc
class IdempotencyKeyTest extends AbstractWarehouseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StockService stockService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private IdempotencyService idempotency;

    private long newStock(int quantity) {
        return stockService.create(new StockLevelRequest(1L, System.nanoTime(), quantity)).id();
    }

    private int quantity(long id) {
        return stockService.get(id).quantity();
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private MockHttpServletRequestBuilder withdraw(long id, int quantity, String key) {
        MockHttpServletRequestBuilder request = post("/api/v1/stock/" + id + "/withdraw")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":" + quantity + "}");
        return key == null ? request : request.header("Idempotency-Key", key);
    }

    @Test
    @DisplayName("The same key and the same request twice: one withdrawal, the second answer is the stored first one")
    void replayReturnsTheFirstResult() throws Exception {
        long id = newStock(10);
        String key = key();

        MvcResult first = mockMvc.perform(withdraw(id, 3, key)).andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replayed")).andReturn();
        MvcResult second = mockMvc.perform(withdraw(id, 3, key)).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true")).andReturn();

        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(quantity(id)).as("withdrawn once, not twice").isEqualTo(7);
    }

    @Test
    @DisplayName("The same key with a different quantity is refused with 422 and changes nothing")
    void differentPayloadIsRefused() throws Exception {
        long id = newStock(10);
        String key = key();
        mockMvc.perform(withdraw(id, 3, key)).andExpect(status().isOk());

        mockMvc.perform(withdraw(id, 4, key)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Idempotency key reused"));

        assertThat(quantity(id)).isEqualTo(7);
    }

    @Test
    @DisplayName("The same key on another stock row, on another operation, or from another caller is refused as well")
    void keyIsBoundToOperationTargetAndCaller() throws Exception {
        long id = newStock(10);
        long other = newStock(10);
        String key = key();
        mockMvc.perform(withdraw(id, 3, key)).andExpect(status().isOk());

        mockMvc.perform(withdraw(other, 3, key)).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/v1/stock/transfer").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromId\":" + id + ",\"toId\":" + other + ",\"quantity\":3}")
                        .header("Idempotency-Key", key))
                .andExpect(status().isUnprocessableEntity());
        String someoneElse = TestTokens.token("another-admin", List.of("ROLE_ADMIN", "ROLE_USER"));
        mockMvc.perform(withdraw(id, 3, key).headers(bearer(someoneElse))).andExpect(status().isUnprocessableEntity());

        assertThat(quantity(id)).isEqualTo(7);
        assertThat(quantity(other)).isEqualTo(10);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return headers;
    }

    @Test
    @DisplayName("20 simultaneous requests with one key: one withdrawal, every caller gets 200 with the same body")
    void concurrentRequestsWithOneKeyExecuteOnce() throws Exception {
        long id = newStock(10);
        String key = key();
        int requests = 20;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return mockMvc.perform(withdraw(id, 3, key)).andReturn();
                }));
            }
            go.countDown();
            int replayed = 0;
            java.util.Set<String> bodies = new java.util.HashSet<>();
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get(60, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                bodies.add(result.getResponse().getContentAsString());
                if ("true".equals(result.getResponse().getHeader("Idempotent-Replayed"))) {
                    replayed++;
                }
            }
            System.out.println("20 simultaneous requests, one key: " + (requests - replayed) + " executed, " + replayed + " replayed");
            assertThat(replayed).isEqualTo(requests - 1);
            assertThat(bodies).hasSize(1);
            assertThat(quantity(id)).isEqualTo(7);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Different keys are different requests, and a request without a key behaves as before")
    void differentKeysAndNoKey() throws Exception {
        long id = newStock(10);

        mockMvc.perform(withdraw(id, 1, key())).andExpect(status().isOk());
        mockMvc.perform(withdraw(id, 1, key())).andExpect(status().isOk());
        mockMvc.perform(withdraw(id, 1, null)).andExpect(status().isOk());
        mockMvc.perform(withdraw(id, 1, null)).andExpect(status().isOk());

        assertThat(quantity(id)).isEqualTo(6);
    }

    @Test
    @DisplayName("A failed request leaves no key behind: the same key can be sent again")
    void failuresAreNotStored() throws Exception {
        long id = newStock(5);
        String key = key();

        mockMvc.perform(withdraw(id, 9, key)).andExpect(status().isConflict());
        mockMvc.perform(withdraw(id, 9, key)).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?", Integer.class, key))
                .isZero();
        assertThat(quantity(id)).isEqualTo(5);
    }

    @Test
    @DisplayName("A key that is empty, too short or has odd characters is a 400")
    void invalidKeysAreRejected() throws Exception {
        long id = newStock(5);

        for (String bad : new String[] {"short", "has space in it", "x".repeat(81), "semi;colon;key"}) {
            mockMvc.perform(withdraw(id, 1, bad)).andExpect(status().isBadRequest());
        }
        assertThat(quantity(id)).isEqualTo(5);
    }

    @Test
    @DisplayName("Old keys are purged, recent ones stay")
    void oldKeysArePurged() throws Exception {
        long id = newStock(10);
        String recent = key();
        String old = key();
        mockMvc.perform(withdraw(id, 1, recent)).andExpect(status().isOk());
        mockMvc.perform(withdraw(id, 1, old)).andExpect(status().isOk());
        jdbc.update("UPDATE idempotency_keys SET created_at = now() - interval '3 days' WHERE idempotency_key = ?", old);

        int purged = idempotency.purgeOlderThan(java.time.Duration.ofHours(24));

        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?", Integer.class, old)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?", Integer.class, recent)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/stock/" + id)).andExpect(jsonPath("$.quantity").value(8));
    }
}
