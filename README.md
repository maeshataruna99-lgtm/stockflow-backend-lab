# StockFlow Backend Lab

Backend-only Java/Spring Boot microservices portfolio project: master-data
services (`catalog-service`, `warehouse-service`) behind an `api-gateway` with a
`discovery-server`, backed by PostgreSQL and Redis, built phase by phase under an
evidence-based acceptance protocol (see [ROADMAP.md](ROADMAP.md)).

Key documents:

- [ROADMAP.md](ROADMAP.md) - phased plan and acceptance gates.
- [docs/STATUS.md](docs/STATUS.md) - what actually exists and what was actually
  verified.
- [docs/architecture/system-overview.md](docs/architecture/system-overview.md) -
  services, ports and request flows.
- [docs/api-contracts/initial-api-outline.md](docs/api-contracts/initial-api-outline.md) -
  endpoint contract that the OpenAPI documents reflect.
- [docs/decisions/](docs/decisions) - pinned versions, data ownership, demo auth
  policy.

## Non-goals

- No frontend; interaction through REST APIs, OpenAPI/Swagger, Bruno or `.http`
  collections.
- No claims of production readiness.
- No secrets or real credentials in Git.
- No cross-service database queries or foreign keys; services own their data.
- No distributed ACID or exactly-once claims without a verifiable mechanism.
- No benchmark claims without reproducible measurements.

## Status (honest summary)

- Phase 0 - repository and design baseline: VERIFIED on 2026-10-28
  (document baseline only; evidence in [docs/STATUS.md](docs/STATUS.md)).
- Phase 1 - Maven/Spring Boot foundations: VERIFIED on 2026-10-28 (four app
  scaffolds, profiles, CI with a real Actions run).
- Phase 2 - PostgreSQL, Flyway and seed data: VERIFIED on 2026-10-28 (gate
  closed; two owned databases, isolated roles, deterministic seeds).
- Phase 3 - JPA persistence, /api/v1 master-data endpoints, Testcontainers:
  VERIFIED on 2026-10-28 (gate closed; catalog 19 tests, warehouse 17 tests in
  CI; OpenAPI served and matching actual behavior).
- Phase 4 - Docker Compose core and service discovery: VERIFIED on
  2026-10-28 (gate closed; multi-stage non-root Dockerfiles, full
  six-service compose stack with internal network, Eureka registration,
  health checks, restart and volume-preservation evidence).
- Phase 5 - API gateway and its ten use cases: VERIFIED on 2026-11-03 (gate
  closed; dynamic routing, JWT authentication and roles, Redis rate limiting,
  circuit breaker, load balancing, timeout and GET-only retry, request ID,
  tracing in logs, one error contract, API versioning; 58 gateway tests, CI run
  41580832608 green). Catalog and warehouse verify the JWT themselves
  (ADR 0007). Known limits are in [docs/STATUS.md](docs/STATUS.md).
- Phase 6 - Redis and caching labs: VERIFIED on 2026-11-03 (gate closed; product
  cache-aside with invalidation after commit and Redis failure handling, eviction
  policy lab, stampede lab with single-flight, two catalog instances on one
  Redis; 60 catalog tests, CI run 41628003809 green). Open items are in
  [docs/STATUS.md](docs/STATUS.md).
- Phase 7 - Database engineering labs: VERIFIED on 2026-11-03 (gate closed; race condition, locking,
  deadlock, isolation levels, connection pooling, N+1, indexes and EXPLAIN ANALYZE, constraints,
  zero-downtime migration, a real read replica with replication lag, and partitioning and sharding as
  isolated experiments; warehouse 100 and catalog 70 tests, CI run 41661725957 green). Open items are in
  [docs/STATUS.md](docs/STATUS.md).
- Phase 8 - Resilience and traffic labs: VERIFIED on 2026-11-04 (gate closed; idempotency keys, retry with backoff and jitter,
  liveness and readiness, graceful shutdown, a scale lab with resource limits, reverse proxy behavior; warehouse 128, catalog 74,
  gateway 68 tests, CI run 41781129839 green). Open items are in [docs/STATUS.md](docs/STATUS.md).
- Phase 9 - Messaging and distributed-system labs: VERIFIED on 2026-11-04 (gate closed; transactional outbox
  `stock_outbox` + `FOR UPDATE SKIP LOCKED` worker + idempotent `stock_inbox`, RabbitMQ queue/DLQ routing under
  `--profile messaging`, HMAC-SHA256 raw-body webhook verification + retry lab, and isolated test-tree labs for
  Saga orchestration, CQRS projection convergence, event-sourced stock ledger, and bounded-queue backpressure;
  warehouse 154 tests, live `labs/messaging/dlq-lab.sh` and `labs/messaging/webhook-lab.sh`). Open items are in
  [docs/STATUS.md](docs/STATUS.md).
- Phase 10 - Observability, docs and release: VERIFIED on 2026-11-04 (gate closed; admin-gated `/actuator/metrics`
  in `compose.labs.yaml`, structured correlation logging with `X-Request-Id`/`traceId`/`spanId` and
  `SensitiveDataRedactor`, zero-dependency Zipkin v2 span export to Jaeger under `--profile observability`,
  all 50 topics in [ROADMAP.md](ROADMAP.md) Section 6 linked to `guidebooks/01..40` and test/lab evidence;
  warehouse 158, catalog 74, gateway 68, discovery 1 tests passing).

## What is core vs isolated lab

- **Core service code** (`apps/api-gateway`, `apps/catalog-service`, `apps/warehouse-service`, `apps/discovery-server`):
  Eureka discovery, dynamic routing, JWT auth & RBAC, Redis rate limiting, circuit breaker & timeout/GET-retry,
  request ID & W3C trace propagation, RFC 7807 `problem+json` errors, API versioning, product cache-aside with
  post-commit invalidation & single-flight stampede protection, pessimistic/optimistic stock locking, bounded
  HikariCP pool, Flyway migrations V1-V8, idempotency keys (`Idempotency-Key`), transactional outbox (`stock_outbox`)
  & idempotent inbox (`stock_inbox`), HMAC-SHA256 webhook verification (`POST /api/v1/webhooks/carrier-events`),
  distinct liveness/readiness probes, graceful shutdown, structured correlation logs with secret redaction, and
  optional Zipkin v2 span export (`stockflow.tracing.zipkin-endpoint`).
- **Opt-in Compose overrides & profiles** (`compose.labs.yaml`, `compose.scale.yaml`, `compose.hostdev.yaml`):
  - `compose.labs.yaml` default services: adds a second `catalog-2` instance and enables `health,info,metrics` (admin-only).
  - `--profile eviction`: starts `redis-lab` (memory-capped Redis for eviction experiments, `labs/caching/eviction-lab.sh`).
  - `--profile replica`: starts `pg-primary` and `pg-replica` (streaming replication lag lab, `labs/database/replica-lab.sh`).
  - `--profile messaging`: starts `rabbitmq` (`rabbitmq:3.13.7-management-alpine` for queue/DLQ lab, `labs/messaging/dlq-lab.sh`).
  - `--profile observability`: starts `jaeger` (`jaegertracing/all-in-one:1.62.0` for distributed tracing lab, `labs/observability/tracing-lab.sh`).
  - `compose.scale.yaml`: multi-instance CPU/memory-capped scale lab (`labs/resilience/scale-lab.sh`).
- **Isolated educational labs in test trees** (intentionally not wired as the primary persistence/runtime model):
  database sharding (`ShardingRoutingLabTest`), table partitioning on archive table (`PartitionPruningLabTest`),
  client-side `BackoffPolicy`/`RetryingCaller`, Saga coordinator (`SagaTest`), CQRS read-model projection
  (`CqrsProjectionTest`), event-sourced stock ledger (`EventSourcedStockLedgerTest`), and bounded-executor
  backpressure (`BackpressureTest`).

## Prerequisites

- Docker Desktop (or any Docker engine) with Compose v2 - the core stack is
  fully containerized.
- JDK 21 LTS + Maven Wrapper only if you want to run tests or build services on
  the host (`.\mvnw.cmd` on Windows, `./mvnw` elsewhere); the compose stack
  itself needs no host JDK.

## Quick start (clean clone -> running core stack)

1. Copy the environment template and fill in per-machine values (all local-only
   placeholders; the stack refuses to start without them):

   ```
   cp .env.example .env   # Windows PowerShell: Copy-Item .env.example .env
   # then set POSTGRES_SUPER_PASSWORD, CATALOG_DB_PASSWORD, WAREHOUSE_DB_PASSWORD
   ```

2. Start the whole core stack (builds the four app images from source first):

   ```
   docker compose up --build -d
   ```

3. Wait until every service reports healthy (usually well under a minute after
   the images are built):

   ```
   docker compose ps
   ```

4. Verify:

   - Gateway (public entry point): http://localhost:8080/actuator/health
   - Eureka dashboard (operator view): http://localhost:8761 - CATALOG-SERVICE,
     WAREHOUSE-SERVICE and API-GATEWAY should be listed UP.
   - In-container health for the internal services:

     ```
     docker compose exec catalog   wget -q http://localhost:8081/actuator/health -O -
     docker compose exec warehouse wget -q http://localhost:8082/actuator/health -O -
     ```

   - OpenAPI documents exist per service inside the network (springdoc);
     from Phase 5 the gateway routes make them reachable through 8080.

### Ports (default stack)

| Service | Port | Published? |
| --- | --- | --- |
| api-gateway | 8080 | yes, 127.0.0.1 (single public entry point) |
| discovery-server (Eureka) | 8761 | yes, 127.0.0.1 (operator dashboard) |
| catalog-service | 8081 | no - internal only |
| warehouse-service | 8082 | no - internal only |
| postgres | 5432 | no - internal only |
| redis | 6379 | no - internal only |

Only the gateway and the Eureka dashboard are published, and only on
127.0.0.1. Service-to-service traffic uses internal DNS names
(`postgres`, `discovery`), never localhost.

### Restarting

- Single service (for example after a code change and rebuild):
  `docker compose build warehouse && docker compose up -d warehouse`
  or `docker compose restart warehouse` for a plain restart. Services
  re-register with Eureka automatically (verified: UP again ~40 s).
- `docker compose stop` / `docker compose start` preserve everything.
- `docker compose down` removes containers and the network but PRESERVES the
  named volumes `stockflow-backend-lab_postgres_data` and
  `stockflow-backend-lab_redis_data` - your databases survive (verified:
  seed and demo data intact across a down/up cycle).

### DESTRUCTIVE cleanup (read before running)

- `docker compose down -v` DELETES the named volumes - every table, seed row
  and demo row in `catalogdb`/`warehousedb` is gone. This is the only supported
  way to get a truly fresh database; it is intentionally not part of any normal
  workflow.
- After `down -v`, the next `up` re-runs the postgres init scripts and the
  Flyway migrations from version 0, recreating the deterministic seed state.

### Host-side development (optional override)

Host-run services (`mvnw spring-boot:run`) cannot reach the internal-only
postgres of the default stack. If you occasionally want to run a single service
on the host against the compose database, publish postgres temporarily:

```
docker compose -f compose.yaml -f compose.hostdev.yaml up -d postgres
```

`compose.hostdev.yaml` publishes postgres on 127.0.0.1:15432 only. It is an
opt-in debugging aid, never part of the default stack, and the Java services
in compose ignore it entirely.

A host-run catalog or warehouse also needs the token key in its environment
(`STOCKFLOW_SECURITY_JWT_SECRET`, same value as `JWT_SECRET`), because both
services verify the gateway's JWT themselves (ADR 0007).

## Security caveats

- A demo root account may exist only in demo/local profiles, sourced from
  environment variables and stored as a strong hash
  (see [docs/decisions/0003-demo-auth.md](docs/decisions/0003-demo-auth.md)).
- `.env.example` contains placeholders only; never commit `.env`.
