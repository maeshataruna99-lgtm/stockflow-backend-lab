package dev.stockflow.warehouse.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.stockflow.warehouse.AbstractWarehouseIntegrationTest;
import dev.stockflow.warehouse.api.StockProperties;
import dev.stockflow.warehouse.api.StockService;
import dev.stockflow.warehouse.api.WebhookSignatureException;
import dev.stockflow.warehouse.api.WebhookVerifier;
import dev.stockflow.warehouse.api.dto.StockLevelRequest;
import dev.stockflow.warehouse.resilience.BackoffPolicy;
import dev.stockflow.warehouse.resilience.RetryingCaller;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Webhook signature verification over the raw request body bytes, replay protection, and idempotent webhook retries
 * (Phase 9, unit 9.3: topics 40 and 41).
 */
class WebhookVerificationAndRetryTest extends AbstractWarehouseIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private StockService stockService;

    @Autowired
    private WebhookVerifier verifier;

    @Autowired
    private StockProperties properties;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvcWithoutJwt() {
        // Webhooks arrive from external senders without a user JWT; authentication is the HMAC signature
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("A valid HMAC-SHA256 over the raw JSON body (including formatting whitespace) is accepted without a JWT")
    void validHmacOverRawBodyAppliesAdjustment() throws Exception {
        long stockId = stockService.create(new StockLevelRequest(1L, System.nanoTime(), 10)).id();
        String prettyRawJson = "{\n  \"eventType\" : \"STOCK_ADJUSTED\",\n  \"stockId\" : " + stockId + ",\n  \"delta\" : -3\n}";
        byte[] rawBytes = prettyRawJson.getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();
        String signature = verifier.sign(timestamp, rawBytes);

        mockMvc.perform(post("/api/v1/webhooks/stock-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Signature", signature)
                        .header("X-Webhook-Timestamp", String.valueOf(timestamp))
                        .header("X-Webhook-Id", "wh-valid-" + stockId)
                        .content(rawBytes))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.quantity").value(7));

        assertThat(stockService.get(stockId).quantity()).isEqualTo(7);
    }

    @Test
    @DisplayName("Signing compact JSON and sending pretty-printed JSON with the same fields fails with 401: raw bytes matter")
    void reformattedWhitespaceWithSameJsonMeaningFailsSignatureCheck() throws Exception {
        long stockId = stockService.create(new StockLevelRequest(1L, System.nanoTime(), 10)).id();
        String compactJson = "{\"eventType\":\"STOCK_ADJUSTED\",\"stockId\":" + stockId + ",\"delta\":-3}";
        String prettyJson = "{ \"eventType\": \"STOCK_ADJUSTED\", \"stockId\": " + stockId + ", \"delta\": -3 }";
        long timestamp = Instant.now().getEpochSecond();
        String signatureForCompact = verifier.sign(timestamp, compactJson.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post("/api/v1/webhooks/stock-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Signature", signatureForCompact)
                        .header("X-Webhook-Timestamp", String.valueOf(timestamp))
                        .header("X-Webhook-Id", "wh-whitespace-" + stockId)
                        .content(prettyJson.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Invalid webhook signature"));

        assertThat(stockService.get(stockId).quantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("Tampered body, wrong secret, and stale timestamp outside the tolerance window are rejected with 401")
    void tamperedBodyWrongSecretAndStaleTimestampAreRejected() throws Exception {
        long stockId = stockService.create(new StockLevelRequest(1L, System.nanoTime(), 10)).id();
        byte[] original = ("{\"eventType\":\"STOCK_ADJUSTED\",\"stockId\":" + stockId + ",\"delta\":-2}")
                .getBytes(StandardCharsets.UTF_8);
        byte[] tampered = ("{\"eventType\":\"STOCK_ADJUSTED\",\"stockId\":" + stockId + ",\"delta\":-9}")
                .getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();

        // 1. Tampered payload with original signature
        mockMvc.perform(post("/api/v1/webhooks/stock-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Signature", verifier.sign(now, original))
                        .header("X-Webhook-Timestamp", String.valueOf(now))
                        .header("X-Webhook-Id", "wh-tampered-" + stockId)
                        .content(tampered))
                .andExpect(status().isUnauthorized());

        // 2. Signed with another secret
        String wrongSecretSig = "sha256=" + WebhookVerifier.hmacHex("wrong_secret_key_for_webhook_test", now, original);
        mockMvc.perform(post("/api/v1/webhooks/stock-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Signature", wrongSecretSig)
                        .header("X-Webhook-Timestamp", String.valueOf(now))
                        .header("X-Webhook-Id", "wh-wrong-secret-" + stockId)
                        .content(original))
                .andExpect(status().isUnauthorized());

        // 3. Valid HMAC for a timestamp 10 minutes in the past (replay tolerance is 5 minutes)
        long staleTimestamp = now - Duration.ofMinutes(10).toSeconds();
        mockMvc.perform(post("/api/v1/webhooks/stock-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Signature", verifier.sign(staleTimestamp, original))
                        .header("X-Webhook-Timestamp", String.valueOf(staleTimestamp))
                        .header("X-Webhook-Id", "wh-stale-" + stockId)
                        .content(original))
                .andExpect(status().isUnauthorized());

        assertThat(stockService.get(stockId).quantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("Constant-time verifier rejects single-character differences and truncated signatures")
    void verifierRejectsNearMissAndTruncatedSignatures() {
        byte[] body = "{\"eventType\":\"PING\"}".getBytes(StandardCharsets.UTF_8);
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        String valid = verifier.sign(now.getEpochSecond(), body);

        verifier.verify(valid, String.valueOf(now.getEpochSecond()), body, now);

        String lastFlip = valid.substring(0, valid.length() - 1) + (valid.endsWith("0") ? "1" : "0");
        assertThatThrownBy(() -> verifier.verify(lastFlip, String.valueOf(now.getEpochSecond()), body, now))
                .isInstanceOf(WebhookSignatureException.class);
        assertThatThrownBy(() -> verifier.verify(valid.substring(0, 20), String.valueOf(now.getEpochSecond()), body, now))
                .isInstanceOf(WebhookSignatureException.class);
        assertThat(properties.webhookTolerance()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("Webhook sender retries transient 503s with backoff and duplicate deliveries with the same X-Webhook-Id apply once")
    void webhookSenderRetriesTransientFailuresAndReceiverDeduplicatesByWebhookId() throws Exception {
        long stockId = stockService.create(new StockLevelRequest(1L, System.nanoTime(), 10)).id();
        String webhookId = "wh-retry-" + stockId;
        byte[] body = ("{\"eventType\":\"STOCK_ADJUSTED\",\"stockId\":" + stockId + ",\"delta\":-4}")
                .getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();
        String signature = verifier.sign(timestamp, body);

        List<Duration> waits = new ArrayList<>();
        BackoffPolicy policy = new BackoffPolicy(Duration.ofMillis(100), Duration.ofSeconds(1), 4,
                BackoffPolicy.Jitter.FULL, new Random(42L));
        RetryingCaller caller = new RetryingCaller(policy, IOException.class::isInstance, waits::add);
        AtomicInteger attempts = new AtomicInteger();

        // Attempts 1 and 2 fail before reaching the receiver (simulated 503/timeout); attempt 3 delivers
        int statusCode = caller.call(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IOException("503 Service Unavailable from receiver");
            }
            return mockMvc.perform(post("/api/v1/webhooks/stock-events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Webhook-Signature", signature)
                            .header("X-Webhook-Timestamp", String.valueOf(timestamp))
                            .header("X-Webhook-Id", webhookId)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.replayed").value(false))
                    .andReturn().getResponse().getStatus();
        });
        assertThat(statusCode).isEqualTo(200);
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(waits).hasSize(2);

        // Sender also retries when the first 200 response was lost on the wire: same X-Webhook-Id arrives a second time
        mockMvc.perform(post("/api/v1/webhooks/stock-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Signature", signature)
                        .header("X-Webhook-Timestamp", String.valueOf(timestamp))
                        .header("X-Webhook-Id", webhookId)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Webhook-Replayed", "true"))
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.quantity").value(6));

        assertThat(stockService.get(stockId).quantity()).isEqualTo(6);
    }
}
