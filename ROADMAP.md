# StockFlow Backend Lab

## Master Implementation Roadmap and Evidence-Based Acceptance Plan

**Document status:** Planning baseline\
**Project type:** Backend-only Java/Spring Boot microservices portfolio\
**Primary objective:** Give an AI coding agent a controlled,
phase-by-phase implementation plan where work is marked complete only
after verifiable evidence exists.

> **Truth rule:** A checkbox is not proof. Never mark a task `VERIFIED`
> unless the exact command or manual experiment has actually been run
> and its result recorded. If execution is unavailable, use `BLOCKED` or
> `IMPLEMENTED - NOT VERIFIED`. Never invent test output, container
> status, screenshots, benchmark results, or Git commits.

------------------------------------------------------------------------

## 1. Project goals

Build a backend engineering laboratory that demonstrates practical
familiarity with Java, Spring Boot, Maven, MVC, JPA, JPQL, PostgreSQL,
Redis, Docker Compose, service discovery, API Gateway, testing, and
distributed-system failure modes.

There is no frontend. Interaction is through REST APIs, Swagger/OpenAPI,
Bruno collections, `.http` request files, scripts, logs, metrics, and
guidebooks.

### Core services

1.  `api-gateway`: single external HTTP entry point, routing and gateway
    experiments.
2.  `discovery-server`: service registry.
3.  `catalog-service`: product, SKU, category and unit master data.
4.  `warehouse-service`: warehouse and storage-location master data.
5.  `postgres`: local PostgreSQL instance with separate logical
    databases and separate least-privilege roles for each service.
6.  `redis`: cache and later distributed-caching labs.

Optional lab-only infrastructure, introduced only when needed: -
RabbitMQ and worker service. - OpenTelemetry Collector and Jaeger. -
PostgreSQL replica or additional PostgreSQL instances for
replication/sharding demonstrations. - Load-testing tools.

### Technology baseline

-   Java 21 LTS.
-   Spring Boot 3.5.x, exact patch pinned in project files.
-   A compatible Spring Cloud release train, exact version pinned.
-   Maven Wrapper committed to each independently built service.
-   Spring MVC, Spring Data JPA, Hibernate, JPQL, Flyway.
-   PostgreSQL 16 and Redis 7, pinned to explicit image tags.
-   Spring Cloud Gateway and Eureka.
-   JUnit 5, Mockito, Spring Boot Test, Spring Security Test and
    Testcontainers.
-   Docker multi-stage builds, Docker Compose and GitHub Actions.
-   Springdoc OpenAPI/Swagger UI.
-   Actuator and Micrometer; OpenTelemetry tracing in a later phase.

The agent must check official compatibility matrices before choosing
exact Spring Boot/Spring Cloud patch versions. Record exact versions in
`docs/decisions/0001-version-baseline.md`. Do not use unpinned `latest`
tags.

------------------------------------------------------------------------

## 2. Non-goals and guardrails

-   No frontend.
-   No claims of production readiness.
-   No secrets committed to Git.
-   No hard-coded root password.
-   No cross-service database queries or foreign keys.
-   No distributed ACID transaction claims.
-   No "exactly once" messaging claims without a precisely defined
    mechanism and proof.
-   No benchmarks or performance claims without reproducible
    measurements.
-   No advanced infrastructure added to the core Compose stack unless a
    roadmap phase needs it.
-   No feature marked verified merely because code compiles.
-   Do not silently replace the selected stack or weaken acceptance
    tests to make a build pass.

### Service data ownership

-   `catalog-service` owns catalog tables.
-   `warehouse-service` owns warehouse/location tables.
-   Each service uses its own logical PostgreSQL database and
    least-privilege database role.
-   Cross-service references use IDs and APIs, not cross-database joins
    or foreign keys.
-   The gateway routes requests but does not own business rules.

### Default root user

A demo root account may exist only in `demo`/`local` profile.
`ROOT_USERNAME` and `ROOT_PASSWORD` come from environment variables.
Hash passwords using a strong `PasswordEncoder`. The root account must
not be seeded in production profile. Never put a working password in
`.env.example`, source code, docs, test logs, or Git history. The
example file contains placeholders only.

Authentication approach must be documented before implementation. A
lightweight local demo authentication/token issuer is acceptable only if
clearly isolated to local/demo profiles. All service endpoints must
still enforce authorization where appropriate. Never expose a public
endpoint that allows arbitrary users to mint a root token.

------------------------------------------------------------------------

## 3. Required repository layout

``` text
stockflow-backend-lab/
├── apps/
│   ├── api-gateway/
│   ├── discovery-server/
│   ├── catalog-service/
│   └── warehouse-service/
├── infrastructure/
│   ├── postgres/init/
│   ├── redis/
│   ├── rabbitmq/
│   └── observability/
├── playground/
│   ├── bruno/environments/
│   ├── bruno/gateway/
│   ├── bruno/catalog/
│   ├── bruno/warehouse/
│   ├── http/
│   └── scripts/
├── labs/
│   ├── database/
│   ├── concurrency/
│   ├── caching/
│   ├── resilience/
│   ├── messaging/
│   └── observability/
├── guidebooks/
├── docs/
│   ├── architecture/
│   ├── decisions/
│   └── api-contracts/
├── .github/workflows/ci.yml
├── compose.yaml
├── compose.labs.yaml
├── .env.example
├── Makefile
├── README.md
└── LICENSE
```

Each application has its own `pom.xml`, Maven Wrapper, Dockerfile,
application configuration, Flyway migrations, and tests. Avoid shared
Java libraries until a concrete need is demonstrated.

Each business service should use a clear MVC/JPA package structure such
as: `config/`, `security/`, `controller/`, `dto/request/`,
`dto/response/`, `service/`, `repository/`, `entity/`, `mapper/`,
`exception/`.

Controllers handle HTTP and DTOs; services own business rules and
transaction boundaries; repositories own persistence access; entities
are never returned directly from public APIs; `@RestControllerAdvice`
defines a consistent error contract.

------------------------------------------------------------------------

## 4. Evidence-based status protocol

Use these status values in `ROADMAP.md` and the project status report:

-   `NOT STARTED`: no implementation attempted.
-   `IN PROGRESS`: work is underway.
-   `IMPLEMENTED - NOT VERIFIED`: code/configuration exists but required
    verification has not passed or has not been run.
-   `BLOCKED`: unable to proceed; include the exact reason and evidence.
-   `VERIFIED`: all acceptance criteria for that task passed and
    evidence is recorded.
-   `DEFERRED`: intentionally postponed, with rationale and dependency
    noted.

### Required evidence for every VERIFIED task

Record: 1. Date/time of verification. 2. Exact command or reproducible
manual steps. 3. Exit code / actual result. 4. Relevant test names and
pass/fail counts. 5. Relevant log excerpt or artifact path when useful.
6. Environment used (OS/runtime/container context). 7. Any limitations.

Never infer a successful test from source inspection. Never write "all
tests passed" unless the full required command actually completed
successfully. If Docker is unavailable, do not claim containers started.
If only unit tests ran, do not claim integration or E2E verification.

Keep `docs/STATUS.md` updated after each phase. Update the roadmap
checkbox only when the status protocol permits it. `VERIFIED` means
green; all other statuses must not be represented by a green
badge/checkmark.

### Definition of done for every task

-   Acceptance criteria are explicit.
-   Tests are written before or alongside implementation; test-first is
    preferred.
-   Targeted tests are run and output is captured honestly.
-   Relevant broader test suite is run.
-   Documentation and API examples are updated.
-   No secret, generated credential, or local data file is committed.
-   Agent summarizes changed files, commands actually run, results,
    blockers, and next task.
-   Do not proceed to the next phase if the current phase's required
    gate fails, unless the user explicitly authorizes a documented
    exception.

------------------------------------------------------------------------

## 5. Roadmap and phase gates

### Phase 0 --- Repository and design baseline

Status: VERIFIED (2026-10-28) - documentation-level acceptance criteria met;
evidence in `docs/STATUS.md`: official-source version pinning (ADR 0001),
consistency review across documents, ignore-rule checks, and
`git status --short` clean of unintended files. No application feature exists
or is claimed.

**Deliverables** - `README.md` with project intent and non-goals. -
`docs/architecture/system-overview.md`. -
`docs/decisions/0001-version-baseline.md`. - `docs/STATUS.md`. -
`ROADMAP.md`. - `.gitignore`, `.env.example`, initial license
decision. - Service ownership and API contract outline.

**Acceptance criteria** - Service boundaries and data ownership are
explicit. - Java/Spring Boot/Spring Cloud versions are compatible and
pinned. - Root user and secret-handling policy is documented. - All
future phases have acceptance gates. - No application feature is claimed
implemented yet.

**Verification** - Review all docs for contradictory versions, service
names, ports, and assumptions. - Check `.gitignore` excludes `.env`,
build outputs, IDE files, local DB dumps and credentials. -
`git status --short` contains only intended files.

### Phase 1 --- Maven/Spring Boot foundations

Status: IN PROGRESS (2026-10-28). JDK prerequisite resolved: the build target
stays Java 21 via `--release 21` while the local default JDK is 25 - decision
in `docs/decisions/0004-jdk-toolchain.md`. Apps are scaffolded one at a time,
each with its own real `mvnw verify` evidence before VERIFIED.

**Deliverables** - Independently buildable applications: gateway,
discovery server, catalog, warehouse. - Maven Wrapper for every
application. - Spring MVC baseline, configuration profiles, Actuator. -
Unit-test baseline and CI workflow.

**Acceptance criteria** - Each app starts locally with documented
config. - Each `./mvnw clean verify` succeeds independently. -
Application context tests pass. - Java and dependency versions are
pinned. - CI executes build/tests for every app and fails on a failed
build/test.

**Verification commands** - Linux/macOS:
`./mvnw --batch-mode clean verify` inside each app. - Windows:
`mvnw.cmd --batch-mode clean verify`. - Run the GitHub Actions workflow
and record its actual result; local success alone does not prove remote
CI succeeded.

### Phase 2 --- PostgreSQL, Flyway and seed data

**Deliverables** - PostgreSQL Compose service with health check and
persistent volume. - Separate logical DBs and least-privilege roles for
catalog and warehouse. - Flyway versioned migrations in each service. -
Deterministic demo seed data. - Local/demo root account seeded safely.

**Acceptance criteria** - Clean database startup creates both
databases/roles correctly. - Flyway migrations run on a fresh volume. -
Restart does not duplicate seed data. - Catalog and warehouse roles
cannot write to each other's database. - Root user is not seeded in
production profile. - No actual secret appears in repository or logs.

**Verification** - Start from a fresh test volume/database. - Inspect
migration history and seed row counts. - Restart services and verify row
counts remain stable. - Test database role permissions. - Test
production profile does not create the demo root user.

### Phase 3 --- MVC/JPA/JPQL master data APIs

**Catalog API minimum** - `GET /api/v1/products` with
pagination/filter. - `POST /api/v1/products`. -
`GET /api/v1/products/{id}`. - `PATCH /api/v1/products/{id}`. -
`GET /api/v1/categories`. - JPQL query for active products by
category/search criteria.

**Warehouse API minimum** - `GET /api/v1/warehouses`. -
`POST /api/v1/warehouses`. - `GET /api/v1/warehouses/{id}`. -
`PATCH /api/v1/warehouses/{id}`. -
`GET /api/v1/warehouses/{id}/locations`. -
`POST /api/v1/warehouses/{id}/locations`.

**Acceptance criteria** - DTO validation returns documented 400
responses. - Missing resource returns 404. - Duplicate SKU/code returns
documented 409. - Public APIs never serialize JPA entities directly. -
Pagination has a maximum page size. - JPQL is used by at least one
documented query. - Repository/service/controller tests cover happy
paths and failure paths. - OpenAPI reflects actual behavior.

### Phase 4 --- Docker Compose core and service discovery

**Deliverables** - Multi-stage Dockerfile per application, non-root
runtime. - Core `compose.yaml`: gateway, discovery, catalog, warehouse,
PostgreSQL, Redis. - Internal Docker network and persistent volumes. -
Eureka registration for services. - Health checks/readiness
configuration. - `.env.example` and local setup instructions.

**Acceptance criteria** - Clean clone can start the core stack using
documented commands. - All core containers become healthy/ready or
expose an accurately documented readiness limitation. - Catalog and
warehouse register with Eureka. - Service-to-service traffic uses
internal DNS/discovery, not hard-coded host-only URLs. - Only required
host ports are published. - Restart behavior is documented and tested. -
`docker compose down` preserves named volumes; destructive cleanup is
separately documented.

**Verification** - `docker compose config`. -
`docker compose up --build -d`. - `docker compose ps`. - Inspect
application logs and Eureka registrations. - Call each service's health
endpoint. - Restart one service and verify registration/recovery.

### Phase 5 --- API Gateway and ten gateway use cases

Implement and test these individually:

1.  Dynamic routing.
2.  JWT authentication and role authorization.
3.  Redis-backed rate limiting.
4.  Circuit breaker and controlled fallback.
5.  Discovery-based load balancing with two service instances.
6.  Timeout and bounded retry for safe/idempotent operations.
7.  Request/response transformation and trace/request ID.
8.  Distributed tracing and correlation ID.
9.  Centralized error contract without leaking internals.
10. API versioning/backward compatibility.

**Acceptance criteria for each use case** - Has a guidebook section. -
Has a Bruno or `.http` request collection. - Has normal and failure-path
reproduction steps. - Has expected HTTP status/body/observable
behavior. - Has automated test where technically appropriate. - Has
actual verification evidence. - Does not claim the feature is active if
only documented.

**Special safeguards** - Never automatically retry non-idempotent writes
unless an idempotency mechanism is verified. - A circuit breaker
fallback must not report a write as successful when it was not. -
Internal Actuator, Eureka and administrative endpoints are not publicly
routed. - Rate-limit keys must not trust arbitrary client-supplied
identity headers. - Load balancing requires two actual instances and
observed distribution.

### Phase 6 --- Redis and caching labs

**Topics** - Cache invalidation. - Redis eviction. - Cache stampede. -
Distributed caching across service instances.

**Acceptance criteria** - Catalog read path demonstrates cache miss then
hit. - Update invalidates or refreshes affected cache entries after
successful DB commit. - Database remains the source of truth. - Redis
failure behavior is explicit and tested. - Eviction lab has a controlled
memory/policy configuration. - Stampede lab includes reproducible
concurrent requests and a measured observation. - Distributed caching
uses multiple app instances and shared Redis.

### Phase 7 --- Database engineering labs

**Topics** - Race conditions, deadlocks, transactions, ACID, isolation
levels. - Optimistic and pessimistic locking. - Connection pooling. -
N+1 queries, indexes, query optimization, EXPLAIN ANALYZE. - Foreign-key
and unique constraints. - Migrations and zero-downtime migration
patterns. - Read replicas and replication lag. - Sharding and
partitioning.

**Acceptance criteria** - Each topic has a separate guidebook/lab,
prerequisites, commands, expected outcome, and trade-offs. - Concurrency
tests use real PostgreSQL via Testcontainers or a documented
equivalent. - Race condition lab demonstrates the bug before the fix and
asserts invariants after the fix. - Deadlock lab is bounded by a timeout
and verifies rollback/recovery. - EXPLAIN ANALYZE output is generated
from the actual query and dataset; no fabricated plan or performance
claim. - Index claims compare actual plans and measured results. -
Zero-downtime migrations use expand/migrate/contract and explain
backward compatibility. - Read replica and replication lag labs use
actual primary/replica infrastructure before being marked verified. -
Sharding and partitioning are isolated experiments, not falsely
represented as core architecture.

### Phase 8 --- Resilience and traffic labs

**Topics** - Idempotency, retry, exponential backoff, jitter, circuit
breakers, rate limiting. - Load balancing, horizontal/vertical scaling,
reverse proxies. - Graceful shutdown and health checks.

**Acceptance criteria** - Retry count and eligible failure classes are
bounded. - Jitter is testable or observable. - Idempotency keys reject
reuse with a different request payload. - Scale lab runs multiple
instances and documents resource limits. - Graceful shutdown has a
reproducible in-flight request/termination test. - Liveness and
readiness are distinct and accurately configured.

### Phase 9 --- Messaging and distributed-system labs

Add optional Compose profile/infrastructure for RabbitMQ and workers.

**Topics** - Message queues, dead-letter queues, workers, job
scheduling. - Event-driven architecture, webhook signature
verification/retries. - Eventual consistency, distributed
transactions/Saga, outbox. - CQRS, event sourcing, backpressure.

**Acceptance criteria** - Message consumer is idempotent. - Retry
exhaustion routes poison messages to a DLQ. - Outbox writes business
data and outbox record in one local DB transaction. - Outbox publisher
handles duplicate delivery. - Saga has explicit states, compensation,
and recovery. - Eventual consistency has a measurable expected
convergence condition. - Webhook signature verification uses the raw
request body and constant-time comparison where applicable. - Event
sourcing is an isolated educational lab, not falsely claimed as the
primary persistence model. - Backpressure lab shows bounded
queue/consumer behavior under load.

### Phase 10 --- Observability, docs and release

**Deliverables** - Actuator health/metrics. - Structured logging with
trace/correlation IDs. - OpenTelemetry/Jaeger lab. - Complete README and
guidebooks. - GitHub Actions CI. - Clean-clone verification and release
checklist.

**Acceptance criteria** - A request can be followed across gateway and
downstream service using trace context. - Sensitive values are
redacted. - All 50 topics have one of `PLANNED`,
`IMPLEMENTED - NOT VERIFIED`, `VERIFIED`, or `DEFERRED`. - README states
exactly what is implemented and what is not. - Clean-clone setup
succeeds in the documented environment. - All verified claims have
evidence.

------------------------------------------------------------------------

## 6. The 50-topic tracking index

Use the exact topic names below in `ROADMAP.md`, each linked to its
guidebook/lab and evidence file.

1.  Race Conditions
2.  Deadlocks
3.  Database Transactions
4.  ACID Properties
5.  Isolation Levels
6.  Optimistic Locking
7.  Pessimistic Locking
8.  Connection Pooling
9.  N+1 Queries
10. Database Indexes
11. Query Optimization
12. EXPLAIN ANALYZE
13. Foreign Key Constraints
14. Unique Constraints
15. Database Migrations
16. Zero-Downtime Migrations
17. Read Replicas
18. Replication Lag
19. Database Sharding
20. Partitioning
21. Cache Invalidation
22. Redis Eviction
23. Cache Stampedes
24. Distributed Caching
25. Idempotency
26. Retry Mechanisms
27. Exponential Backoff
28. Jitter
29. Circuit Breakers
30. Rate Limiting
31. Load Balancing
32. Horizontal Scaling
33. Vertical Scaling
34. Reverse Proxies
35. Message Queues
36. Dead Letter Queues
37. Background Workers
38. Job Scheduling
39. Event-Driven Architecture
40. Webhook Verification
41. Webhook Retries
42. Eventual Consistency
43. Distributed Transactions
44. Outbox Pattern
45. CQRS
46. Event Sourcing
47. Backpressure
48. Graceful Shutdown
49. Health Checks
50. Distributed Tracing

------------------------------------------------------------------------

## 7. CI/CD and verification rules

The GitHub Actions workflow must: - Build and test each service
independently. - Use Java 21 and Maven Wrapper. - Run
`./mvnw --batch-mode verify`. - Run integration tests requiring
PostgreSQL through Testcontainers when supported by the runner. - Fail
on test failures; do not convert failures to warnings just to obtain a
green check. - Avoid exposing secrets in logs. - Optionally build Docker
images after tests pass.

Recommended commands:

``` bash
# In each app directory
./mvnw --batch-mode clean verify

# Validate Compose syntax
docker compose config

# Start core stack
docker compose up --build -d

# Inspect running containers
docker compose ps

# Inspect logs
docker compose logs --tail=200 api-gateway catalog-service warehouse-service
```

A local successful run does not prove GitHub Actions passed. Record the
actual CI run URL or status if remote CI is available. If it is not
available, report local verification only.

------------------------------------------------------------------------

## 8. Required project documents

-   `README.md`: project purpose, architecture, prerequisites, startup,
    playground, security caveats, current status.
-   `ROADMAP.md`: phase/task checkboxes and status values.
-   `docs/STATUS.md`: last verified commit, current phase, commands run,
    actual results, blockers, next task.
-   `docs/architecture/system-overview.md`: container diagram, service
    boundaries, ports, request flows.
-   `docs/decisions/0001-version-baseline.md`: exact version
    compatibility decisions.
-   `docs/decisions/0002-data-ownership.md`: data ownership and database
    boundaries.
-   `docs/decisions/0003-demo-auth.md`: root/demo authentication and
    limitations.
-   `guidebooks/`: concept, implementation, reproduction, expected
    result, failure path, trade-offs, interview questions.
-   `playground/`: runnable API requests with safe local environment
    placeholders.
-   `docs/evidence/`: test outputs, sanitized logs, benchmark metadata
    or screenshots when appropriate. Never commit secrets or massive raw
    logs.

------------------------------------------------------------------------

## 9. Required agent operating prompt

Use the companion file `AI_AGENT_HANDOFF_PROMPT.md`. It tells the coding
agent to read the roadmap first, work one phase at a time, test before
claiming success, preserve honest statuses, and stop at failed phase
gates.

## 10. Final acceptance rule

The project is ready for a portfolio release only when: - The core stack
can be built and started from a clean clone. - API requests can exercise
both master data services through the gateway. - Security, seed data and
database ownership rules are verified. - CI builds and tests the
services. - Every claimed gateway use case has reproducible evidence. -
The 50-topic index distinguishes verified labs from planned/deferred
topics. - README and guidebooks accurately describe the code that
exists.

A smaller set of rigorously verified labs is better than 50 unchecked
claims.
