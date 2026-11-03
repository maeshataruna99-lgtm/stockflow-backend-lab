# Guidebook 04: Circuit Breaker and Controlled Fallback in API Gateway

## 1. Concept

A circuit breaker watches calls to a downstream service. While calls succeed it is closed. When the failure rate in a sliding window crosses a threshold it opens: further calls are rejected at once, without touching the downstream. After a wait it lets a few probe calls through (half-open) and closes again if they succeed.

A fallback is what the caller gets while the circuit is open or a call failed. Here it is always `503 Service Unavailable` as `application/problem+json`. It never returns data that looks like success, so a failed write (POST, PATCH) is never reported as done.

## 2. Implementation

- `GatewayRoutesConfig.protect(...)` applies, per route, the rate limiter and then a Resilience4j circuit breaker named after the route (`catalog-products`, `catalog-categories`, `warehouse`). Each route has its own circuit.
- Failures counted: exceptions (connection refused, no instance in Eureka), timeouts, and downstream statuses 500, 502, 503, 504. 4xx responses are not failures.
- `GatewayResilienceConfig` builds the default Resilience4j config from `CircuitBreakerProperties` (`stockflow.gateway.circuit-breaker.*`): `sliding-window-size` 10, `minimum-number-of-calls` 5, `failure-rate-threshold` 50, `wait-duration-in-open-state` 10s, `timeout` 2s.
- `FallbackController` answers `/fallback` with the 503 problem body.
- No retry is configured, so a write is sent at most once. Bounded retry for safe operations is use case 6.

## 3. Reproduction

Automated, no Docker needed:

```bash
./mvnw --batch-mode test -Dtest=CircuitBreakerGatewayTest
```

`CircuitBreakerGatewayTest` (3 tests, WireMock as the downstream, small window):
- Repeated downstream 500 returns 503 and, once open, the downstream receives no further calls (exactly 2 hits for 3 requests).
- A downstream slower than the timeout returns 503.
- A POST to a failing downstream returns 503 and the downstream saw it exactly once.

Manual, against the compose stack: stop `catalog`, call `GET /api/v1/products` repeatedly, then start `catalog` again. Requests in `playground/http/gateway-circuit-breaker.http`.

## 4. Failure modes and edge cases

- With the circuit open, a request that would have worked is rejected until the wait ends. That is the trade for protecting the downstream.
- A downstream 500 on a write may mean the write happened before the error. The 503 tells the client the outcome is unknown; the client must check before retrying.
- `/fallback` is reachable directly and returns the same 503 without a token. It carries no data.
- Circuit state is per gateway instance, not shared through Redis.

## 5. Trade-offs

- Small window: reacts fast but opens on a few unlucky calls. Large window: stable but slow to react.
- Short timeout protects threads and latency but counts slow-but-healthy calls as failures.
- Counting 5xx makes the gateway hide the downstream's own error body behind a generic 503.

## 6. Interview questions

- What are the three circuit states and what moves it between them?
- Why must a fallback for a POST not return a cached or empty success?
- Why is a circuit per route better than one per gateway?
- How does a circuit breaker differ from a retry, and why can retries make an outage worse?
