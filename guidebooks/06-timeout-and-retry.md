# Guidebook 06: Timeout and Bounded Retry for Safe Operations

## 1. Concept

A timeout bounds how long the gateway waits for a downstream. A retry repeats a failed call. Retry is only safe for operations that give the same result when repeated (GET). Repeating a POST can create the resource twice, because a failed response does not prove the write did not happen. So the gateway retries GET and nothing else, with a hard limit and a growing pause between attempts.

## 2. Implementation

- Timeouts: `spring.cloud.gateway.server.webflux.httpclient.connect-timeout` (2000 ms) and `response-timeout` (1s) in `application.yml`. A downstream that does not answer within 1 s counts as failed.
- Retry: `GatewayRoutesConfig.protect(...)` adds `retry` after the circuit breaker, so the breaker sees the final outcome. Settings in `RetryProperties` (`stockflow.gateway.retry.*`): `retries` 2 (3 attempts in total), `first-backoff` 100 ms doubling up to `max-backoff` 1 s.
- Retried: method GET; statuses 502, 503, 504; timeouts and I/O errors. Not retried: any other method, 500 and 4xx.
- The circuit breaker timeout (`stockflow.gateway.circuit-breaker.timeout`, now 5 s) covers all attempts: 3 x 1 s plus backoff. If it were lower, it would cut retries short.
- With two instances (unit 5.5) the retry is sent again through the load balancer, so it usually lands on the other instance. That is the failover.

## 3. Reproduction

Automated:

```bash
./mvnw --batch-mode test -Dtest=RetryTimeoutGatewayTest
```

`RetryTimeoutGatewayTest` (4 tests, WireMock downstream):
- A GET that fails once with 503 is retried and the client gets 200 (2 downstream hits).
- A GET that always fails with 503 is sent exactly 3 times, then 503.
- A POST failing with 503 is sent exactly once.
- A downstream slower than the response timeout is attempted 3 times, then 503.

Live: start the second catalog instance (guidebook 05), stop it, and send GETs at once. Requests in `playground/http/gateway-retry-timeout.http`.

## 4. Failure modes and edge cases

- Retrying multiplies load on a struggling downstream. The limit of 2 retries and the backoff keep that bounded; the circuit breaker stops it once the failure rate is high.
- A GET with a side effect would be unsafe to retry. Catalog and warehouse GETs have none.
- A retried request can still end in 503 after about 3.4 s (3 timeouts plus backoff).
- Worst-case latency grows: keep `retries x response-timeout` below the breaker timeout.

## 5. Trade-offs

- More retries hide more transient errors but raise latency and load.
- Retrying only 502/503/504 skips 500 on purpose: a 500 is often a deterministic bug, and repeating it does not help.
- Without an idempotency key, writes cannot be retried safely. Idempotency keys belong to Phase 8.

## 6. Interview questions

- Why is retrying a POST dangerous, and what would make it safe?
- Why exponential backoff, and what does jitter add?
- How can a retry make an outage worse (retry storm), and what limits it here?
- Why must the circuit breaker timeout be larger than retries x timeout?
