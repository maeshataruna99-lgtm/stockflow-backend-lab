# Guidebook 08: Distributed Tracing and Correlation

## 1. Concept

A distributed trace follows one request across processes. Each process records a span (one unit of work) that belongs to the trace through a shared trace id. The caller sends the trace context in the W3C `traceparent` header: `00-<trace id, 32 hex>-<parent span id, 16 hex>-<flags>`. The receiver keeps the trace id and creates a new span id.

Two identifiers exist here and do different jobs:
- `X-Request-Id` (unit 5.7): chosen at the edge, visible to the client, used to quote a request in a support ticket.
- Trace id (`traceparent`): internal, follows the request through gateway, catalog and warehouse, and appears in every log line written while the request is handled.

## 2. Implementation

- `micrometer-tracing-bridge-otel` (version from the Spring Boot BOM) in `api-gateway`, `catalog-service` and `warehouse-service`. Spring Boot then creates server spans, the gateway creates a client span and injects `traceparent` into the downstream request, and Boot adds `[traceId-spanId]` to each log line.
- `management.tracing.sampling.probability: 1.0` in the three `application.yml` files: every request is traced. This is a lab setting; Boot's default is 0.1.
- `spring.reactor.context-propagation: auto` in the gateway: without it the reactive gateway does not put the trace id into its log lines.
- No exporter, collector or Jaeger yet. Spans stay in the process and show up as ids in the logs. Exporting and viewing spans is the Phase 10 observability lab.

## 3. Reproduction

Automated:

```bash
./mvnw --batch-mode test -Dtest=TracingGatewayTest
```

`TracingGatewayTest` (2 tests, WireMock downstream): a request without trace context makes the downstream receive a valid `traceparent`; a request with a `traceparent` keeps the same trace id downstream with a new span id.

Live, with request-level logs on (`compose.labs.yaml`):

```bash
docker compose -f compose.yaml -f compose.labs.yaml up --build -d
```

Send `playground/http/gateway-tracing.http` request 2 (it carries trace id `4bf92f3577b34da6a3ce929d0e0e4736`), then search both logs:

```bash
docker compose logs gateway --no-log-prefix | grep 4bf92f3577b34da6a3ce929d0e0e4736
```

```bash
docker compose logs catalog --no-log-prefix | grep 4bf92f3577b34da6a3ce929d0e0e4736
```

Both show the same trace id with different span ids.

## 4. Failure modes and edge cases

- Spring Boot tests switch tracing off unless the test class has `@AutoConfigureObservability`.
- A client can send any trace id, so it is for correlation only, never for authorization.
- With sampling below 1.0 the trace id still travels and is logged, but spans are not recorded for exporting.
- Environment variables lowercase logger names, so debug logging by env var must name a package, not a class.
- Lines logged outside a request (startup, scheduled work) have an empty trace slot.

## 5. Trade-offs

- Sampling 100% is simple and costs memory and export volume at real traffic; production usually samples a fraction.
- Logs-only tracing needs no extra infrastructure but you search logs by hand; a collector and a UI make the span tree visible.
- Context propagation in the reactive gateway adds a small overhead per operator.

## 6. Interview questions

- What is the difference between a trace id, a span id and a correlation id?
- What does the `traceparent` header contain, and who creates the new span id?
- Why does a reactive application need context propagation to log the trace id?
- Why sample, and what do you lose when you do?
