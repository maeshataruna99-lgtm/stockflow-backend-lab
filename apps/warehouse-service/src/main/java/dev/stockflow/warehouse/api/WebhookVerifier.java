package dev.stockflow.warehouse.api;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Verifies inbound webhook signatures over the exact raw request body bytes (Phase 9, unit 9.3).
 *
 * <p>The signed message is {@code <timestampEpochSeconds>.<rawBodyBytes>}. Why the raw bytes and not a parsed DTO:
 * JSON allows arbitrary whitespace and key order, so re-serializing a parsed object produces different bytes and
 * breaks valid signatures. Comparison uses {@link MessageDigest#isEqual(byte[], byte[])} so response time does not
 * leak how many prefix bytes of the HMAC matched. A timestamp outside {@code webhookTolerance} is refused even when
 * the HMAC is valid, bounding replay of a captured request.
 */
@Component
public class WebhookVerifier {

    private static final String PREFIX = "sha256=";
    private static final String HMAC_ALGO = "HmacSHA256";

    private final StockProperties properties;

    WebhookVerifier(StockProperties properties) {
        this.properties = properties;
    }

    public String sign(long timestampEpochSeconds, byte[] rawBody) {
        return PREFIX + hmacHex(properties.webhookSecret(), timestampEpochSeconds, rawBody);
    }

    public void verify(String signatureHeader, String timestampHeader, byte[] rawBody, Instant now) {
        if (signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
            throw new WebhookSignatureException("Missing or malformed X-Webhook-Signature header");
        }
        if (timestampHeader == null || timestampHeader.isBlank()) {
            throw new WebhookSignatureException("Missing X-Webhook-Timestamp header");
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            throw new WebhookSignatureException("X-Webhook-Timestamp must be epoch seconds");
        }
        long driftSeconds = Math.abs(now.getEpochSecond() - timestamp);
        if (driftSeconds > properties.webhookTolerance().toSeconds()) {
            throw new WebhookSignatureException("Webhook timestamp is outside the replay tolerance window");
        }
        byte[] expected = sign(timestamp, rawBody != null ? rawBody : new byte[0]).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signatureHeader.trim().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new WebhookSignatureException("Webhook signature does not match the raw request body");
        }
    }

    public static String hmacHex(String secret, long timestampEpochSeconds, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            mac.update((timestampEpochSeconds + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(rawBody);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("cannot compute HMAC-SHA256", e);
        }
    }
}
