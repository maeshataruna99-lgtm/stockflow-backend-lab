package dev.stockflow.warehouse.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.stockflow.warehouse.AbstractWarehouseIntegrationTest;
import dev.stockflow.warehouse.api.StockService;
import dev.stockflow.warehouse.api.dto.StockLevelRequest;
import dev.stockflow.warehouse.resilience.BackoffPolicy.Jitter;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A write may only be retried automatically when repeating it is safe. The classic failure: the server did the work, the
 * response was lost, the client times out and sends it again. Here the first response is dropped after the server has
 * processed the request (an IOException on the client side), and the retry is sent with and without an Idempotency-Key.
 */
@AutoConfigureMockMvc
class SafeRetryTest extends AbstractWarehouseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StockService stockService;

    private long newStock(int quantity) {
        return stockService.create(new StockLevelRequest(1L, System.nanoTime(), quantity)).id();
    }

    /** Sends the withdrawal; the first answer is lost on its way back. */
    private int sendWithdrawal(long id, String key, AtomicInteger attempts) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/stock/" + id + "/withdraw")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":3}");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        int status = mockMvc.perform(request).andReturn().getResponse().getStatus();
        if (attempts.incrementAndGet() == 1) {
            throw new IOException("read timed out: the response never arrived");
        }
        return status;
    }

    private RetryingCaller caller(List<Duration> slept) {
        return new RetryingCaller(new BackoffPolicy(Duration.ofMillis(20), Duration.ofMillis(200), 3, Jitter.FULL,
                new SplittableRandom(5)), e -> e instanceof IOException, slept::add);
    }

    @Test
    @DisplayName("Retried with the same Idempotency-Key, a write whose response was lost is applied once")
    void retryWithAKeyAppliesOnce() throws Exception {
        long id = newStock(10);
        String key = "retry-" + UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        List<Duration> slept = new ArrayList<>();

        int status = caller(slept).call(() -> sendWithdrawal(id, key, attempts));

        assertThat(status).isEqualTo(200);
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(slept).hasSize(1);
        assertThat(stockService.get(id).quantity()).as("withdrawn once").isEqualTo(7);
    }

    @Test
    @DisplayName("The same retry without a key withdraws twice: why the gateway never retries a POST by itself")
    void retryWithoutAKeyAppliesTwice() throws Exception {
        long id = newStock(10);
        AtomicInteger attempts = new AtomicInteger();

        int status = caller(new ArrayList<>()).call(() -> sendWithdrawal(id, null, attempts));

        assertThat(status).isEqualTo(200);
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(stockService.get(id).quantity()).as("withdrawn twice").isEqualTo(4);
    }
}
