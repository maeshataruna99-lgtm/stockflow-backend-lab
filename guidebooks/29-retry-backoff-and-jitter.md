# Guidebook 29: Retry, Exponential Backoff and Jitter

## 1. Concept

A retry repeats a failed call in the hope that the failure was transient. It helps against a dropped connection or a restarting instance, and it hurts when it is unbounded or synchronised: a service that is struggling receives its normal load plus every client's retries, and may never recover (a retry storm).

Rules for a retry that helps:
- Bound it: a fixed number of attempts, never "until it works".
- Retry only failures that can be transient (connection errors, timeouts, 502, 503, 504), not a 400 or a 404.
- Retry only operations that are safe to repeat: reads, or writes protected by an idempotency key (guidebook 28).
- Back off exponentially: wait longer after each failure (100 ms, 200 ms, 400 ms, ...) up to a cap, so a struggling service gets room.
- Add jitter: randomise the wait. Without it all clients that failed together retry at the same instants and hit the service as a synchronised wave. Full jitter picks a uniform value between 0 and the backoff; equal jitter keeps half of the backoff and randomises the other half.

## 2. Implementation

Gateway (production code, unit 5.6 and this unit):
- `GatewayRoutesConfig.protect`: retry only `GET`, only when the answer is 502, 503 or 504 (or a timeout), at most `stockflow.gateway.retry.retries` (2) extra attempts, exponential backoff from `first-backoff` (100 ms) doubling up to `max-backoff` (1 s), inside the circuit breaker.
- New in this unit: `stockflow.gateway.retry.jitter` (default 0.5) is passed to the filter. The library keeps each jittered wait between the first backoff and the maximum, so jitter never makes a wait shorter than `first-backoff`: with a 200 ms backoff the first retry waits between 200 and 300 ms, the second between 200 and 600 ms.
- A write is never retried by the gateway.

Client side (lab code in the test tree of warehouse-service, `dev.stockflow.warehouse.resilience`): `BackoffPolicy` (exponential backoff with cap, jitter NONE, FULL or EQUAL, an injected random source) and `RetryingCaller` (bounded attempts, a predicate for the eligible failure classes, the wait passed to a callback so a test does not sleep). They are not used by the services; they exist to test and demonstrate the policy.

## 3. Measured observation

3.1 Gateway jitter, read from outside: WireMock's journal records when each attempt of a failing GET arrived (backoff 200 ms, jitter 0.5, 10 requests per run; the first request after startup is excluded because it waits longer on a cold JVM, measured 419 ms and 438 ms):

| Run | First retry waits (ms) | Second retry waits (ms) |
|---|---|---|
| Jitter 0.5, run 1 | 219, 263, 284, 274, 311, 245, 277, 253, 240, 267 | 515, 313, 391, 425, 497, 579, 577, 361, 372, 233 |
| Jitter 0.5, run 2 | 256, 317, 236, 258, 291, 265, 266, 234, 262, 310 | 295, 625, 536, 438, 600, 449, 344, 302, 565, 313 |
| Jitter 0, run 1 | 213, 221, 224, 219, 209, 221, 217, 222, 223, 219 | 421, 424, 420, 408, 416, 420, 422, 419, 421, 418 |
| Jitter 0, run 2 | 220, 218, 216, 212, 224, 219, 220, 216, 219, 239 | 420, 422, 423, 419, 413, 419, 421, 419, 422, 415 |

With jitter the waits differ from request to request and stay inside the bounds; without it they are the backoff plus a few milliseconds of processing (the extra 10 to 40 ms over 200 and 400 is processing in the gateway and WireMock).

Before the property was wired in, the same test with `jitter=0.5` showed constant waits of 208 to 222 ms: the setting was silently ignored.

3.2 Client policy (`BackoffPolicyTest`): without jitter the waits are 100, 200, 400, 500, 500 ms for base 100 ms and cap 500 ms. With full jitter and a raw wait of 400 ms, 10,000 draws averaged 200.3 ms and stayed within [0, 400]; equal jitter stayed within [200, 400]. An action that always fails is called exactly `maxAttempts` times; a non-eligible exception is thrown after one call; a call that succeeds on the third attempt waits twice.

3.3 Why jitter (`ThunderingHerdSimulationTest`, simulated clock, fixed seed, 1,000 clients that all failed at time 0, 5 attempts, peak number of retry requests in one time bucket):

| Backoff and bucket | No jitter | Equal jitter | Full jitter |
|---|---|---|---|
| Base 1 s, 100 ms buckets | 1000 | 217 | 150 |
| Base 100 ms, 50 ms buckets | 1000 | 1000 | 705 |

Jitter spreads the clients only over the width of the backoff window. With a 100 ms backoff the whole window is two buckets wide, so even full jitter leaves a peak of 705 of 1000; with a 1 s backoff it brings the peak down to 150. A short base backoff does not protect a server from a synchronised herd.

3.4 Safe retry of a write (`SafeRetryTest`): the first response is dropped after the server processed the request; the retry with the same `Idempotency-Key` leaves the stock at 7 (10 - 3), the same retry without a key at 4 (10 - 6).

Single machine, a few runs, simulated or loopback; the figures show the shape, not production behavior.

## 4. Reproduction

Gateway, in `apps/api-gateway`:

```bash
./mvnw --batch-mode test -Dtest=RetryJitterGatewayTest,RetryWithoutJitterGatewayTest,RetryTimeoutGatewayTest
```

Client policy, in `apps/warehouse-service`:

```bash
./mvnw --batch-mode test -Dtest=BackoffPolicyTest,ThunderingHerdSimulationTest,SafeRetryTest
```

## 5. Failure modes and edge cases

- Retries multiply across layers: a client with 3 attempts calling a gateway with 3 attempts calling a service gives up to 9 calls to the service for one user request. Retry at one layer, or budget the total.
- A retry without a deadline keeps a request alive for the sum of all waits; the gateway bounds it with the response timeout and the breaker (unit 5.4, 5.6).
- A circuit breaker (guidebook 04) stops retries from piling on a dead service; retries inside a breaker should be counted once.
- Jitter makes tests non-deterministic; inject the random source (the client policy does) or assert ranges (the gateway test does).
- `Retry-After` from the server should override the client's own backoff when present; not done here.

## 6. Interview questions

- What makes a retry help, and what makes it a retry storm?
- Why exponential backoff, and why add jitter on top?
- What is the difference between full and equal jitter?
- Which requests may an automatic retry repeat?
- How do you test code whose delays are random?
