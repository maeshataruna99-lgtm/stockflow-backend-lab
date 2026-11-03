# Guidebook 09: Centralized Error Contract

## 1. Concept

Every error the gateway produces itself has one shape, whatever caused it: an RFC 7807 `application/problem+json` body with `type`, `title`, `status`, `detail` and `requestId`. The text is fixed per status, so an exception message (a host name, a connection string, a stack trace) can never reach the client. The real cause goes to the gateway log, together with the request ID, so an operator can find it from the ID the client quotes.

Errors that a downstream service produces (for example a catalog 404 for a missing product) are passed through unchanged; they are the service contract.

## 2. Implementation

- `ProblemWriter`: builds and writes the problem body. `title` is the HTTP reason phrase, `detail` is fixed text per status (`detailOf`), `requestId` comes from the request ID (unit 5.7).
- `GatewayErrorHandler` (`@Order(-2)`, ahead of the Spring Boot default handler): any exception becomes a problem with the right status (`ResponseStatusException` keeps its status, everything else is 500). 5xx are logged with the full exception; 4xx at debug level.
- `RateLimitProblemFilter`: the rate limiter ends a rejected request with an empty body; this filter gives that 429 the same body.
- `JwtAuthenticationFilter` and `FallbackController` use the same writer and texts, so 401, 403 and 503 match. The 401 and 403 keep their own `type` URN.
- `RequestIdFilter` is now a web filter, because a request with no matching route never reaches the gateway filter chain and would otherwise have no ID on its 404.

Status table:

| Status | Source | detail |
|---|---|---|
| 401, 403 | JWT filter | specific text from the filter |
| 404 | no route matches | No route matches this request. |
| 429 | rate limiter | Too many requests. Slow down and retry later. |
| 500 | unexpected exception | Unexpected gateway error. |
| 502, 504 | gateway exception | fixed text per status |
| 503 | circuit breaker fallback | The downstream service is unavailable. Retry later. |

## 3. Reproduction

Automated:

```bash
./mvnw --batch-mode test -Dtest=ErrorContractGatewayTest
```

`ErrorContractGatewayTest` (6 tests): the shape (all fields present, `requestId` equal to the `X-Request-Id` header, none of the Spring default `timestamp`, `path`, `error`, `exception`, `trace`, `message`) for 404, 429, 401 and 503; an unexpected exception carrying a connection string and a password produces a 500 whose body contains neither; a `ResponseStatusException` with an internal address in its reason keeps the status and drops the address.

Live: `playground/http/gateway-error-contract.http`.

## 4. Failure modes and edge cases

- Once a response is committed (headers sent) the handler cannot change it and re-raises the error.
- The fixed text is less helpful for debugging than the real message; the request ID is the link to the log.
- Downstream error bodies are not rewritten, so they can differ in shape from the gateway own errors.
- Validation errors (400) come from the services, not the gateway.

## 5. Trade-offs

- One shape makes clients simple and prevents leaks, at the cost of hiding detail from the caller.
- A decorator for the 429 is a small workaround for a limiter that writes no body; replacing the limiter filter would be more code.

## 6. Interview questions

- Why return a fixed `detail` instead of the exception message?
- What does RFC 7807 add over an ad-hoc JSON error?
- Why give every error a request ID?
- Why does a 404 for an unknown path need a web filter, not a gateway filter, to carry the ID?
