# Guidebook 07: Request ID and Request/Response Transformation

## 1. Concept

One request passes through several processes. A request ID is a value created at the edge and copied into every hop, so log lines from different services can be matched to the same request. Transformation means the gateway changes headers on the way: adding the ID to the request, and cleaning or hardening the response before the client sees it.

## 2. Implementation

- `RequestIdFilter` (global filter, highest precedence, so it runs before authentication and rate limiting):
  - Reads `X-Request-Id`. It is kept only if it matches `[A-Za-z0-9._-]{1,64}`; otherwise a random UUID is generated. A hostile value (spaces, markup, newlines, very long) is never echoed or logged.
  - Sets the header on the request sent downstream.
  - Sets it once on the response in `beforeCommit`, so it appears even on answers the gateway writes itself (401, 429, 503) and replaces any value the downstream sent.
- Response transformation in `GatewayRoutesConfig.protect(...)`: `Server` header removed (does not reveal the downstream stack), `X-Content-Type-Options: nosniff` added.
- This is request correlation only. Trace context (W3C `traceparent`, spans) is use case 8.

## 3. Reproduction

Automated:

```bash
./mvnw --batch-mode test -Dtest=RequestIdGatewayTest
```

`RequestIdGatewayTest` (5 tests): ID generated when absent and seen downstream and in the response; a valid client ID kept end to end; a malformed client ID replaced; a 401 produced by the gateway carries an ID; the downstream `Server` header removed and `nosniff` added.

Live: `playground/http/gateway-request-id.http`.

## 4. Failure modes and edge cases

- A client can choose its own ID, so the ID is for correlation, never for authorization or uniqueness guarantees.
- Downstream services do not log the ID yet; they only receive it. Logging it comes with structured logging in Phase 10.
- Requests that never reach the gateway filter chain (for example a connection refused at the port) have no ID.

## 5. Trade-offs

- Accepting a client ID lets a caller correlate across systems; generating always is stricter but loses that. Validation keeps the first without the risk.
- Removing `Server` is cosmetic hardening; it does not replace fixing real vulnerabilities.

## 6. Interview questions

- Why generate the ID at the gateway and not in each service?
- Why validate an incoming ID instead of trusting it?
- What is the difference between a correlation ID and a distributed trace?
- Why set the response header in `beforeCommit`?
