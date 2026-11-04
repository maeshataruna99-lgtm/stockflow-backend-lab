# Guidebook 35: Webhook Signature Verification and Retries

## 1. Concept

A webhook is an outbound HTTP `POST` sent by one system to notify another of an event. Unlike internal service calls on a private network, a webhook receiver exposes an endpoint to an external sender and faces three hazards (topics 40 and 41):
1. **Forged or tampered requests**: anyone who learns the webhook URL can POST fake payloads unless the request is authenticated with a cryptographic signature (`HMAC-SHA256`).
2. **Re-serialization mismatch**: the sender computes the HMAC over the exact raw bytes it puts on the wire. If the receiver parses the JSON into a Java DTO first and re-serializes it to check the signature, any difference in whitespace, field order, or number formatting changes the bytes and breaks valid signatures.
3. **Timing side-channel and replay attacks**: comparing two hex strings with `String.equals` returns on the first mismatched byte, leaking prefix length through response time; comparing without a signed timestamp allows an attacker who captures a valid request to replay it hours later.
4. **Duplicate deliveries on retry**: webhook senders retry on `5xx` or network timeouts. If the first `200 OK` was lost on the wire, the sender sends the same webhook again, so the receiver must deduplicate by the webhook's unique event ID (`X-Webhook-Id`).

## 2. Implementation

Production code in `warehouse-service` (unit 9.3):
- `POST /api/v1/webhooks/stock-events` (`WebhookController`, permitted without a bearer JWT in `SecurityConfig` because authentication is the HMAC signature).
- `WebhookVerifier`:
  - Reads `@RequestBody byte[] rawBody`, `X-Webhook-Signature` (`sha256=<hex>`), and `X-Webhook-Timestamp` (epoch seconds).
  - Refuses missing headers or a timestamp outside `±stockflow.stock.webhook-tolerance` (default `5m`) with `401 Unauthorized` (`WebhookSignatureException` -> RFC 7807 `ProblemDetail`, title `"Invalid webhook signature"`).
  - Computes `HMAC-SHA256(webhookSecret, timestamp + "." + rawBody)` over the exact `byte[]` received on the wire, and compares expected vs actual bytes with `MessageDigest.isEqual(expected, actual)` (constant-time comparison).
- Only after `verifier.verify(...)` succeeds does `WebhookController` parse `rawBody` with `ObjectMapper` and call `OutboxService.consumeOnce("webhook:" + webhookId, ...)` in one transaction with `StockService.applyDelta(...)`. A duplicate `X-Webhook-Id` returns `200 OK` with header `Webhook-Replayed: true` and `"replayed": true` without modifying stock a second time.

## 3. Measured observation

Automated test (`WebhookVerificationAndRetryTest`, 5 tests, 0.921 s) and live lab (`labs/messaging/webhook-lab.sh`, 2 runs against the running `warehouse` container):

| Scenario | Result |
|---|---|
| Pretty-printed JSON body with spaces/newlines signed over its exact raw bytes | `200 OK`, `replayed=false`, stock `10 -> 7` |
| Same JSON fields and values (`stockId`, `delta: -3`), signed as compact JSON but sent with extra spaces | `401 Unauthorized` (`Invalid webhook signature`), stock stays `7` |
| Tampered `delta: -9` with original signature | `401 Unauthorized`, stock unchanged |
| Signed with a wrong secret | `401 Unauthorized`, stock unchanged |
| Valid HMAC for a timestamp 10 minutes old (tolerance 5 minutes) | `401 Unauthorized`, stock unchanged |
| Outbound sender (`RetryingCaller` + full-jitter `BackoffPolicy`) hits two `503` failures, succeeds on attempt 3 (`delta: -4`), then sends the same `X-Webhook-Id` again | 3 attempts (2 backoff waits), first delivery reduces stock `10 -> 6`, second delivery returns `200 OK` with `Webhook-Replayed: true` and leaves stock at `6` |

## 4. Reproduction

Automated, in `apps/warehouse-service`:

```bash
./mvnw --batch-mode test -Dtest=WebhookVerificationAndRetryTest
```

Live against the Compose stack:

```bash
labs/messaging/webhook-lab.sh <admin-token>
```

## 5. Failure modes and edge cases

- Including the timestamp inside the signed payload (`timestamp + "." + rawBody`) prevents an attacker from taking an old valid signature and pairing it with a fresh `X-Webhook-Timestamp` header.
- `MessageDigest.isEqual` in Java 21 is constant-time when both byte arrays have the same length, and returns `false` immediately when lengths differ (which leaks only the expected hex length, a public constant of SHA-256).
- Returning `2xx` on a duplicate `X-Webhook-Id` tells the sender to stop retrying; returning `409` would make naive senders keep retrying until their retry budget is exhausted.

## 6. Interview questions

- Why must webhook signature verification run on the raw request body bytes rather than a deserialized object?
- Why is `MessageDigest.isEqual` used instead of `String.equals` when checking an HMAC?
- How does signing `<timestamp>.<rawBody>` together with a tolerance window stop replay attacks?
- Why should a webhook receiver return `200 OK` when it sees an `X-Webhook-Id` it already processed?
