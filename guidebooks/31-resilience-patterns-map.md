# Guidebook 31: How the Resilience Patterns Fit Together (map of Phase 5 and Phase 8)

This guidebook adds no new code. It connects the patterns that are verified in other units, so that each Phase 8 topic points to the place where it is built and measured, and shows the order in which they act on one request.

## 1. The order on one request

For a request to a protected route, the gateway applies, in this order (`GatewayRoutesConfig.protect` and the global filters):

1. `RequestIdFilter`: the request gets an `X-Request-Id` (unit 5.7).
2. `JwtAuthenticationFilter`: the token is verified, the identity headers are set from it (unit 5.2). (It and the next filter have the same precedence, so their relative order is not defined; neither depends on the other.)
3. `ClientAddressFilter`: `X-Client-Ip` is set from the TCP peer (unit 8.7, guidebook 32).
4. Rate limiter (Redis token bucket, keyed by user or address): over the limit, 429, the request goes no further (unit 5.3). If Redis is down the limiter lets requests through.
5. Circuit breaker, per route: while open it answers 503 at once without calling the service (unit 5.4).
6. Retry, inside the breaker, for `GET` only, on 502, 503, 504 and timeouts, at most 2 extra attempts, exponential backoff with jitter (unit 5.6 and 8.2). The breaker therefore counts the final outcome of a request, not each attempt.
7. Load balancing: `lb://` picks an instance from the registry, round robin (unit 5.5).
8. The call to the service has a connect timeout (2 s) and a response timeout (1 s).

At the service: the probe endpoints tell the platform whether to send traffic (unit 8.4), idempotency keys make a repeated write safe (unit 8.1), and a SIGTERM drains the requests in flight (unit 8.5).

## 2. Where each Phase 8 point is built and measured

| Topic | Built and measured in | Evidence recorded there |
|---|---|---|
| Idempotency | unit 8.1, guidebook 28 | replay, 422 on a different payload, 1 executed and 19 replayed of 20 simultaneous |
| Retry, bounded, eligible failures | unit 5.6 (guidebook 06) and unit 8.2 (guidebook 29) | 3 attempts in total then 503; a POST is sent once; a hung downstream ends in 503 in 3.4 s |
| Exponential backoff and jitter | unit 8.2, guidebook 29 | waits 219 to 317 ms with jitter, 209 to 239 ms without; herd peak 1000 to 150 |
| Circuit breaker | unit 5.4, guidebook 04 | catalog stopped: 503 from the fallback, recovery to 200 |
| Rate limiting | unit 5.3, guidebook 03 | real Redis: 21 of 80 answered 429, later 40 of 80 |
| Load balancing | unit 5.5, guidebook 05 | two containers, 10 and 10 of 20 requests |
| Horizontal and vertical scaling | unit 8.6, guidebook 30 | one CPU-limited instance against two, three and one bigger instance |
| Reverse proxy | unit 8.7, guidebook 32 | client address passed on, forged headers dropped |
| Graceful shutdown | unit 8.5, guidebook 27 | 10 of 10 in-flight requests answered with `docker stop`, 0 of 10 with `docker kill` |
| Liveness and readiness | unit 8.4, guidebook 26 | frozen database: readiness 503, liveness 200 |

## 3. What the combination does not cover

- Retries multiply across layers: a client that retries and a gateway that retries both act on the same failure (guidebook 29).
- The registry is not connected to readiness, so a service that reports itself not ready is still routed to until it leaves Eureka (guidebook 26).
- The circuit breaker state and the load balancer's instance list are per gateway instance (noted in the Phase 5 gate).
- Rate limiting protects the gateway's callers from one another, not the services from a single caller that goes around the gateway; the services check the token themselves (ADR 0007) but have no limiter of their own.
