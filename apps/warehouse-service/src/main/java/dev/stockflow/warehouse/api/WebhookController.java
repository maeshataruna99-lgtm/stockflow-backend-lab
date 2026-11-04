package dev.stockflow.warehouse.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.stockflow.warehouse.api.dto.StockLevelResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound webhook endpoint (Phase 9, unit 9.3). Authenticates the sender by verifying {@code X-Webhook-Signature}
 * (HMAC-SHA256) over {@code X-Webhook-Timestamp} and the raw request body bytes before parsing JSON, and deduplicates
 * retries by {@code X-Webhook-Id} in {@code processed_events}.
 */
@Tag(name = "Webhooks", description = "Signed inbound stock event webhooks (warehouse-service)")
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_.:-]{4,80}");

    public record StockWebhookEvent(String eventType, Long stockId, Integer delta) {
    }

    private final WebhookVerifier verifier;
    private final OutboxService outbox;
    private final StockService stockService;
    private final ObjectMapper mapper;

    WebhookController(WebhookVerifier verifier, OutboxService outbox, StockService stockService, ObjectMapper mapper) {
        this.verifier = verifier;
        this.outbox = outbox;
        this.stockService = stockService;
        this.mapper = mapper;
    }

    @Operation(summary = "Receive a signed stock adjustment webhook")
    @PostMapping("/stock-events")
    ResponseEntity<Map<String, Object>> receiveStockEvent(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestHeader(value = "X-Webhook-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Webhook-Id", required = false) String webhookId) {
        byte[] body = rawBody != null ? rawBody : new byte[0];
        verifier.verify(signature, timestamp, body, Instant.now());
        if (webhookId == null || !VALID_ID.matcher(webhookId).matches()) {
            throw new InvalidIdempotencyKeyException(
                    "X-Webhook-Id must be 4 to 80 characters of letters, digits and _ . : -");
        }
        StockWebhookEvent event;
        try {
            event = mapper.readValue(body, StockWebhookEvent.class);
        } catch (IOException e) {
            throw new InvalidIdempotencyKeyException("Malformed webhook JSON body");
        }
        if (event.eventType() == null || event.eventType().isBlank()
                || event.stockId() == null || event.delta() == null || event.delta() == 0) {
            throw new InvalidIdempotencyKeyException("eventType, stockId and a non-zero delta are required");
        }
        boolean applied = outbox.consumeOnce("webhook:" + webhookId, event.eventType(),
                () -> stockService.applyDelta(event.stockId(), event.delta()));
        StockLevelResponse current = stockService.get(event.stockId());
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (!applied) {
            response.header("Webhook-Replayed", "true");
        }
        return response.body(Map.of(
                "webhookId", webhookId,
                "replayed", !applied,
                "stockId", current.id(),
                "quantity", current.quantity()));
    }
}
