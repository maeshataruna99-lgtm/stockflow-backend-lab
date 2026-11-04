# Guidebook 40: Observability, Redaction, and Jaeger Distributed Tracing

## 1. Concept

Observability in a multi-service backend rests on three signals plus one hygiene rule:

1. **Actuator health and metrics**: Liveness (`/actuator/health/liveness`) proves the JVM is not wedged; readiness (`/actuator/health/readiness`) proves dependencies are reachable; `/actuator/metrics` exposes JVM, HikariCP, and HTTP request counters/timers to admins in lab/diagnostic mode while keeping `/actuator/env` and `/actuator/beans` closed.
2. **Structured correlation logging (`X-Request-Id`, `traceId`, `spanId`)**: Every HTTP request carries a sanitized `X-Request-Id` for client-facing support tickets and a W3C `traceparent` (`00-<traceId>-<spanId>-01`) for internal distributed tracing. Both IDs are placed in SLF4J `MDC` and emitted on structured `key=value` log lines (`event=http_request service=warehouse-service requestId=... traceId=... spanId=...`).
3. **Span export to a collector (Jaeger)**: Log correlation tells you *which* services touched a request; exporting OpenTelemetry `SpanData` to Jaeger shows the parent-child span tree, per-hop latency, and tags in one queryable trace (`GET /api/traces/<traceId>`).
4. **Sensitive data redaction**: Observability pipelines must never become credential leaks. Headers (`Authorization`, `Cookie`, `Set-Cookie`, `X-Webhook-Signature`), query parameters, JSON fields (`password`, `secret`, `token`, `accessToken`, `refreshToken`), Bearer tokens, and HMAC digests (`sha256=...`) are replaced with `[REDACTED]` before reaching logs or exported span tags.

## 2. Implementation

- **Zero-dependency Zipkin v2 HTTP Exporter (`ZipkinSpanExporterConfig`)**:
  - `api-gateway`, `catalog-service`, and `warehouse-service` already include `io.micrometer:micrometer-tracing-bridge-otel`.
  - When `stockflow.tracing.zipkin-endpoint` is set (for example via `compose.labs.yaml` to `http://jaeger:9411/api/v2/spans`), each service registers a `SimpleSpanProcessor` backed by `ZipkinHttpSpanExporter`, posting finished spans over HTTP/1.1 (`HttpClient.Version.HTTP_1_1`) to Jaeger's Zipkin collector port (`COLLECTOR_ZIPKIN_HOST_PORT=:9411`).
  - Export errors are swallowed (`CompletableResultCode.ofFailure()`) so a Jaeger outage never fails a user request.
- **Correlation and Redaction (`RequestCorrelationFilter` & `SensitiveDataRedactor`)**:
  - `RequestCorrelationFilter` in `warehouse-service` validates incoming `X-Request-Id` against `[A-Za-z0-9._-]{1,64}` (generating a UUID if absent or unsafe), echoes `X-Request-Id` and `X-Trace-Id` on the HTTP response, tags the active OpenTelemetry span with `http.request_id`, and writes a structured access log line with all sensitive headers/parameters redacted by `SensitiveDataRedactor`.
- **Admin-Gated Metrics**:
  - `SecurityConfig` in `catalog-service` and `warehouse-service` permits `/actuator/health/**` and `/actuator/info` without a token, requires `ROLE_ADMIN` for `/actuator/metrics/**`, and denies `/actuator/env` and all other unmapped paths.

## 3. Reproduction

Automated integration and Testcontainers tests (`JaegerTracingExportTest` runs against `jaegertracing/all-in-one:1.62.0`):

```bash
cd apps/warehouse-service
./mvnw --batch-mode test -Dtest=WarehouseSecurityTest,ObservabilityAndRedactionTest,JaegerTracingExportTest
```

Live lab across `api-gateway`, `warehouse-service`, and `jaeger`:

```bash
docker compose -f compose.yaml -f compose.labs.yaml --profile observability up --build -d
bash labs/observability/tracing-lab.sh
```

`labs/observability/tracing-lab.sh` sends an authenticated request with a known `traceparent` and `X-Request-Id` through `api-gateway` (`http://localhost:8080/api/v1/warehouses?password=shouldBeRedacted`), queries `http://jaeger:16686/api/traces/<traceId>`, and prints both the multi-service Jaeger trace (`"serviceName":"api-gateway"`, `"serviceName":"warehouse-service"`) and the redacted structured log line from `warehouse-service`.

## 4. Failure modes and edge cases

- **Collector unavailable**: If Jaeger is stopped or unreachable, `ZipkinHttpSpanExporter` times out after 2–3 seconds and drops the span without throwing into the request thread.
- **Log injection / header spoofing via `X-Request-Id`**: A caller sending newlines or control characters in `X-Request-Id` is rejected by `[A-Za-z0-9._-]{1,64}` and replaced with a fresh server-generated UUID.
- **Unredacted query strings or custom headers**: URLs like `?password=...` or headers like `Authorization: Bearer ...` and `X-Webhook-Signature: sha256=...` are scrubbed by `SensitiveDataRedactor` before formatting the log line or span tag map.
- **Over-exposing Actuator endpoints**: Exposing `*` on Actuator leaks environment variables (`/actuator/env`) and heap dumps (`/actuator/heapdump`). Only `health,info` are exposed in the core config, and `health,info,metrics` in `compose.labs.yaml` behind `ROLE_ADMIN`.

## 5. Trade-offs

- **`SimpleSpanProcessor` vs `BatchSpanProcessor`**: `SimpleSpanProcessor` exports each span immediately on completion, which makes lab scripts and integration tests deterministic without waiting for a 5-second batch flush timer. High-throughput production services prefer `BatchSpanProcessor` with bounded queues.
- **100% sampling (`probability: 1.0`) vs head/tail sampling**: Sampling every request ensures every lab call appears in Jaeger immediately, at the cost of higher network and storage overhead per request.

## 6. Interview questions

- How do `X-Request-Id` and W3C `traceparent` complement each other across an API gateway and downstream services?
- What happens to application latency and availability if your tracing collector slows down or crashes?
- Which fields and headers must be redacted before logs and trace spans leave a service, and why is redacting at the logger/exporter boundary safer than relying on developers to remember at each log call?
- Why should `/actuator/health/liveness` and `/actuator/health/readiness` remain public while `/actuator/metrics` requires authentication and `/actuator/env` stays disabled?
