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
- Phases 5-10: NOT STARTED. Gateway use cases, caching, demo auth, Kubernetes,
  hardening follow the ROADMAP order.

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

## Security caveats

- A demo root account may exist only in demo/local profiles, sourced from
  environment variables and stored as a strong hash
  (see [docs/decisions/0003-demo-auth.md](docs/decisions/0003-demo-auth.md)).
- `.env.example` contains placeholders only; never commit `.env`.
