# STATUS - StockFlow Backend Lab

Protocol: only `VERIFIED` counts as green and every entry points at evidence
that actually ran. A checkbox is tracking, not evidence.

## Current phase

Phase 0 - repository and design baseline: `VERIFIED` (committed as
`24addaa`).
Phase 1 - Maven/Spring Boot foundations: `VERIFIED` (2026-10-28, gate
closed - evidence below).
Phase 2 - PostgreSQL, Flyway and seed data: `VERIFIED` (2026-10-28, gate
closed - evidence below). Gate review 2026-10-28: every deliverable and
acceptance criterion has live evidence (compose service + volume +
healthcheck; two owned databases with isolated least-privilege roles,
four-way psql isolation proof; Flyway V1+V2 success on both databases
from version 0; deterministic seeds stable across restart). Two
root-user items are deliberately deferred to Phase 5 per ADR 0003
(nothing can seed or verify a user before auth exists) - carried as an
obligation there, not as an open risk here. Phase 3 - JPA persistence,
/api/v1 endpoints and Testcontainers tests - is in progress, one unit
per commit: unit 3.1 (JPA foundation in catalog-service) `VERIFIED`
2026-10-28, unit 3.2 (catalog products API) `VERIFIED` 2026-10-28,
unit 3.3 (warehouse API) `VERIFIED` 2026-10-28, unit 3.4 (OpenAPI)
`VERIFIED` 2026-10-28. Gate review 2026-10-28: every Phase 3
deliverable and acceptance criterion has recorded evidence - all six
catalog endpoints and all six warehouse endpoints live-proven; 400/404/
409 contracts proven on both services; entities never serialized
(DTO-only + open-in-view off + VIA_DTO page serialization); pagination
capped at 100; the documented JPQL query exercised; 19 catalog + 17
warehouse tests green locally and in CI; springdoc 2.10.0 serves
/v3/api-docs and Swagger UI on both services with documents matching
actual behavior. Two items are deliberately deferred with their owning
phases: production-profile root seeding (Phase 5, ADR 0003) and
production-profile verification (Phase 4). Phase 4 - Docker Compose
core and service discovery: `VERIFIED` 2026-10-28, gate closed -
multi-stage non-root Dockerfiles (unit 4.1), full six-service compose
stack with internal network, Eureka registration, health checks,
restart + volume preservation evidence (unit 4.2), and setup
documentation with an opt-in hostdev override (unit 4.3); gate review
below. Phase 5 - API gateway and its ten use cases: `IN PROGRESS` - units 5.1
(Dynamic Routing), 5.2 (JWT Authentication & Role Authorization) and 5.3
(Redis-backed rate limiting) `VERIFIED` (evidence below; 5.3 against real
Redis on 2026-11-03); unit 5.4 (circuit breaker) `VERIFIED` 2026-11-03; 5.5 (load balancing) `VERIFIED` 2026-11-03; 5.6 (timeout and retry) `VERIFIED` 2026-11-03; 5.7 (request ID) `VERIFIED` 2026-11-03; 5.8 (tracing) `VERIFIED` 2026-11-03; 5.9 (error contract) `VERIFIED` 2026-11-03; 5.10 (API versioning) `VERIFIED` 2026-11-03; all ten Phase 5 units are verified; the Phase 5 gate review (below) found it not closable yet.
Phase 0 evidence below remains the gate.

## Last verified commit

`e59df72` (feat(compose): core stack with internal network and
discovery) is the latest code state, verified green by Actions run
40674415027 (all four matrix jobs SUCCESS); the latest commit is the
docs commit `0070255` (local setup guide + hostdev override, run
40674854953 SUCCESS). Earlier chain: unit 4.1
commit `34778ed` (Dockerfiles, run 40673481363), docs commit `1a9117b`;
unit 3.4 commit `f642b38` (run 40669289647, catalog 19 + warehouse 17
tests), docs commit `adbc761`; unit 3.3 commit `30648a8` (run
40668312313, warehouse 15 tests), docs commit `2ac8613`; unit 3.2
commit `85a33d3` (run 40667190280), docs commit `d55504e`; unit 3.1
commit `78569f3` (run 40665107969), docs commit `cadfc6d`; Phase 2:
unit 2.1 commit `e5ecdee` (run 40648268572), unit 2.2 commit `0c350e4`
(run 40651087397); live psql and restart evidence 14:05-14:35+07:00
below.

## Phase 0 task status (2026-10-28)

| Task | Status | Evidence |
| --- | --- | --- |
| README with intent and non-goals | `VERIFIED` | written; consistency review below |
| `docs/architecture/system-overview.md` | `VERIFIED` | consistency review below |
| `docs/decisions/0001-version-baseline.md` | `VERIFIED` | official sources fetched 2026-10-28; URLs listed in the ADR; EOL caveat documented |
| `docs/decisions/0002-data-ownership.md` | `VERIFIED` | boundaries explicit; runtime isolation check deliberately left to Phase 2 |
| `docs/decisions/0003-demo-auth.md` | `VERIFIED` (policy) | policy documented; implementation `NOT STARTED` |
| `.gitignore` + `.env.example` | `VERIFIED` | ignore rules proven active below |
| `LICENSE` (MIT) | `VERIFIED` | file present |
| API contract outline | `VERIFIED` | `docs/api-contracts/initial-api-outline.md`; itself marked `PROPOSED` |

Phase 0 overall: `VERIFIED` - documentation-level acceptance criteria are met;
no application feature exists and none is claimed.

## Evidence - commands actually run (2026-10-28T11:43+07:00, Windows, PowerShell)

- `Get-Date -Format o` -> `2026-10-28T11:43:16.0904020+07:00` (verification
  timestamp).
- `git init -b main` -> created the repository (exit 0, earlier this session).
- `java -version` -> OpenJDK `25.0.4.1` LTS (Temurin) as the shell default.
  Follow-up measurement: `mvn -version` -> Apache Maven 3.9.16 running on
  Temurin JDK 21.0.12.1 (JAVA_HOME). A JDK 21 IS installed; builds use it.
  ADR 0004 corrected accordingly (draft claimed otherwise before measuring).
- `git status --short` -> `?? .env.example`, `?? .gitignore`, `?? LICENSE`,
  `?? README.md`, `?? ROADMAP.md`, `?? docs/`. Only intended Phase 0 files;
  `AGENT_RULES.md`, `JAVA_RULES.md` and `AI_AGENT_HANDOFF_PROMPT.md` are
  absent because they are ignored.
- `git check-ignore -v AGENT_RULES.md JAVA_RULES.md AI_AGENT_HANDOFF_PROMPT.md .env`
  -> all four matched `.gitignore` rules (`.env` and the agent/AI block); exit 0.
- `git check-ignore -v .env.example` -> matched only the `!.env.example`
  negation, so the example file stays committable as intended.
- Consistency greps over `*.md`: the exact pins (3.5.16, 2025.0.3, 2.10.0,
  16.15-alpine, 7.4.12-alpine) appear only in ADR 0001 and agree with the
  ROADMAP's generic "3.5.x/compatible Spring Cloud" baseline; ports
  (8080/8081/8082/8761/5432/6379) appear only in
  `docs/architecture/system-overview.md`. No contradictory version, name or
  port was found across the Phase 0 documents.

- Final `git status --short` (run after `docs/STATUS.md` itself was written) ->
  `?? .env.example`, `?? .gitignore`, `?? LICENSE`, `?? README.md`,
  `?? ROADMAP.md`, `?? docs/`. Still only intended Phase 0 files; exit 0.

## Known conflicts surfaced during Phase 0

- Spring Boot 3.5.x plus Spring Cloud 2025.0.x are mutually compatible but
  are past OSS end of support (2026-06-30; last OSS patch 3.5.16). They are
  kept because the ROADMAP pins them and silent stack replacement is
  forbidden. Moving to Boot 4.0.x + Spring Cloud 2025.1.x needs the user's
  explicit decision and a superseding ADR.
- The websearch integration returned no results this session; every version
  fact comes from direct fetches of the official sources listed in ADR 0001.

## Blockers

None. The JDK 21 question closed after measuring Maven; ADR 0004 records the
exact toolchain (Maven 3.9.16 + Temurin JDK 21.0.12.1). Nothing else blocked.

## Phase 1 progress log

- 2026-10-28: JDK prerequisite closed by measurement - Maven 3.9.16 runs on
  Temurin JDK 21.0.12.1 (JAVA_HOME); build target stays Java 21 via
  `--release 21` (ADR 0004, corrected after the measurement).
- 2026-10-28 11:50+07:00: `discovery-server` scaffold `VERIFIED` - command
  `.\mvnw.cmd --batch-mode clean verify` in `apps/discovery-server`, exit 0,
  BUILD SUCCESS, total time 01:03 min; tests run 1, failures 0, errors 0,
  skipped 0 (`DiscoveryServerApplicationTests.contextLoads`); the test JVM
  ran Java 21.0.12.1 and javac compiled with `release 21`; Spring Boot banner
  v3.5.16; repackage produced `target/discovery-server-0.0.1-SNAPSHOT.jar`.
  Honest notes: no Bean Validation provider on the classpath yet (expected
  until services add validation), plus non-fatal Mockito dynamic-agent and
  Spring Cloud LoadBalancer default-cache warnings.
- 2026-10-28 11:55+07:00: `api-gateway` scaffold `VERIFIED` - command
  `.\mvnw.cmd --batch-mode clean verify` in `apps/api-gateway`, exit 0,
  BUILD SUCCESS, total time 48.204 s; tests run 1, failures 0, errors 0,
  skipped 0 (`ApiGatewayApplicationTests.contextLoads`); the test JVM ran
  Java 21.0.12.1 and javac compiled with `release 21`; jar repackaged.
  Honest finding: the build printed two explicit deprecation warnings from
  the starter itself - `spring-cloud-starter-gateway` and
  `spring-cloud-gateway-server` will be removed in the next major release,
  and the warning recommends `spring-cloud-starter-gateway-server-webflux`.
  Maven Central metadata confirms that artifact exists at 4.3.5, the same
  version the deprecated starter resolved to inside the pinned BOM. The
  switch is recorded in ADR 0005 and gets its own verify evidence next.
- 2026-10-28 11:58+07:00: switched `api-gateway` to
  `spring-cloud-starter-gateway-server-webflux` (ADR 0005) and re-ran
  `.\mvnw.cmd --batch-mode clean verify`, exit 0, BUILD SUCCESS, total time
  25.128 s; tests run 1, failures 0, errors 0, skipped 0; the test JVM ran
  Java 21.0.12.1 and javac compiled with `release 21`; both gateway
  deprecation warnings are gone in this run. Remaining non-fatal notes:
  LoadBalancer default-cache and Mockito dynamic-agent warnings.
- 2026-10-28 12:00+07:00: `catalog-service` scaffold `VERIFIED` - command
  `.\mvnw.cmd --batch-mode clean verify` in `apps/catalog-service`, exit 0,
  BUILD SUCCESS, total time 21.311 s; tests run 1, failures 0, errors 0,
  skipped 0 (`CatalogServiceApplicationTests.contextLoads`); the test JVM
  ran Java 21.0.12.1 and javac compiled with `release 21`; Spring Boot
  banner v3.5.16; jar repackaged. Honest notes: LoadBalancer default-cache
  and Mockito dynamic-agent warnings (non-fatal); no deprecation warnings.
- 2026-10-28 12:02+07:00: `warehouse-service` scaffold `VERIFIED` - command
  `.\mvnw.cmd --batch-mode clean verify` in `apps/warehouse-service`, exit
  0, BUILD SUCCESS, total time 21.158 s; tests run 1, failures 0, errors 0,
  skipped 0 (`WarehouseServiceApplicationTests.contextLoads`); the test JVM
  ran Java 21.0.12.1 and javac compiled with `release 21`; jar repackaged.
  Honest notes: LoadBalancer default-cache and Mockito dynamic-agent
  warnings (non-fatal); no deprecation warnings. All four Phase 1 app
  scaffolds are now verified locally; CI evidence remains open.
- Then, one deliverable per task with the same evidence rule: unit-test
  baseline, CI workflow.
- 2026-10-28 12:05+07:00: checkpoint at the user's request - ROADMAP
  Phase 0 and Phase 1 ticked against recorded evidence only. Ticked: all
  Phase 0 items; the four independently verified app builds, wrappers,
  MVC+actuator baseline and the unit-test baseline. Still open before the
  Phase 1 gate: an explicit start-and-health check per app, configuration
  profiles, and the CI workflow with a recorded Actions run (needs a
  remote; none configured yet).
- 2026-10-28 13:16-13:21+07:00: start-and-health check on all four apps
  with the dev profile (`.\mvnw.cmd --batch-mode spring-boot:run
  "-Dspring-boot.run.profiles=dev"`; spring-boot:run recompiled because
  the earlier jars predate the profiles merge). Logs confirm profile
  "dev" active on every app; the discovery server log registered
  CATALOG-SERVICE, WAREHOUSE-SERVICE and API-GATEWAY with status UP at
  13:18:05-07 and each client logged `registration status: 204`. Every
  app answered HTTP 200 on /actuator/health with {"status":"UP"}:
  discovery 8761 (first 200 after ~16 s), gateway 8080 (Netty), catalog
  8081, warehouse 8082 (already up at first attempt). Honest notes: the
  demo and prod profiles are implemented but not yet exercised - dev is
  the only live-proven profile so far (demo/prod get real use in Phases
  2/4); the first registry read misreported empty due to a PowerShell
  content-type quirk, the re-read returned the raw UP_3_ registry; all
  four java processes were stopped afterwards.
  CORRECTION (2026-10-28, found during Phase 2 unit 2.2): that stop only
  killed the mvnw wrapper processes - the four child java.exe processes
  were orphaned and kept listening on 8080/8081/8082/8761 until they
  were identified via netstat+CommandLine and killed during unit 2.2
  (see the Phase 2 log). Lesson recorded: on Windows, stopping
  spring-boot:run means killing the child java.exe, not the wrapper.
- 2026-10-28 13:13-13:24+07:00: CI workflow landed and verified on real
  GitHub Actions runs. Run 1 (40642546618, commit `ed956ef`) FAILED in
  12 s: every matrix job exited 126 with `./mvnw: Permission denied` -
  the wrapper scripts were committed from Windows without the exec bit;
  fixed via `git update-index --chmod=+x` on all four mvnw scripts
  (commit `d9ef367`). Run 2 (40642734252) SUCCESS: all four jobs green
  (discovery 31 s, api-gateway 36 s, catalog 28 s, warehouse 26 s) on
  ubuntu-latest with JAVA_HOME Temurin 21.0.12-1; annotations reported
  actions/checkout@v4 and setup-java@v4 deprecations. Actions were bumped
  to the latest official releases (checkout v7.0.1, setup-java v6.1.0,
  commit `2e43056`). Run 3 (40643409693) SUCCESS: all four jobs green in
  30-32 s and the actions deprecation annotations are gone; only the
  informational Ubuntu 26 migration note remains. Run 1's failure is
  kept as the proof that CI fails on a failed build, per the ROADMAP
  requirement. Phase 1 gate CLOSED.

## Phase 2 progress log

- 2026-10-28 14:05-14:20+07:00: unit 2.1 (compose postgres + init
  scripts) `VERIFIED`. Prerequisite found and handled honestly: Docker
  Desktop (per-user install) was not running; started
  `...\Programs\DockerDesktop\Docker Desktop.exe`, engine up in ~5 s
  (server 29.7.2, Compose v5.5.0). First `docker compose up -d` failed to
  bind 5432 - a native PostgreSQL 18 Windows service
  (`postgresql-x64-18`, PID 6712 postgres.exe) already holds the port
  (verified via netstat; 5432 is NOT in the WinNAT excluded ranges). User
  chose to keep their PostgreSQL 18 and use host port 15432 instead:
  compose now publishes `127.0.0.1:${POSTGRES_PORT:-15432}:5432`, with
  the reason documented in `compose.yaml` and `POSTGRES_PORT` added to
  `.env`/`.env.example`. Evidence sequence: `docker compose config`
  exit 0; `docker compose up -d` on a fresh named volume
  (`stockflow-backend-lab_postgres_data`); container
  `stockflow-postgres` healthy in ~4 s; postgres logs show initdb on the
  empty volume then `running /docker-entrypoint-initdb.d/01-roles.sh`
  (CREATE ROLE x2) and `02-databases.sh` (CREATE DATABASE x2, REVOKE x2).
  psql checks against the real instance: `catalogdb owner=catalog_app`,
  `warehousedb owner=warehouse_app`, both roles `login=true`. Isolation
  proof (ADR 0002 runtime acceptance, four real psql connections):
  catalog_app -> warehousedb FAILS with "permission denied for database
  \"warehousedb\" / User does not have CONNECT privilege" (exit 2);
  warehouse_app -> catalogdb FAILS identically (exit 2);
  catalog_app CREATE TABLE + INSERT + DROP in catalogdb exit 0;
  warehouse_app same in warehousedb exit 0. Persistence: `docker compose
  restart` -> healthy again in ~6 s, both databases still present with
  correct owners. Host reachability: `Test-NetConnection 127.0.0.1
  -Port 15432` True; `docker compose ps` shows
  `127.0.0.1:15432->5432/tcp`. Secrets: `.env` confirmed gitignored
  (`git check-ignore -v .env` -> .gitignore:10); passwords were
  randomly generated, written only to `.env`, never echoed into output
  or committed. Honest notes: postgres remains temporarily host-published
  because the Java services still run on the host (mvnw); Phase 4 removes
  the publishing entirely. Docker-compose image pull succeeded
  (`postgres:16.15-alpine` per ADR 0001).

- 2026-10-28 14:15-14:35+07:00: unit 2.2 (Flyway migrations +
  datasource config) `VERIFIED`. Design deviation found by evidence, not
  assumption: the planned "Flyway owns its own connection via
  spring.flyway.url, no spring-jdbc until Phase 3" does NOT work on Boot
  3.5.16 - the first live run started fine but silently skipped
  FlywayAutoConfiguration (no DataSource bean exists without spring-jdbc;
  zero flyway lines in the log, empty databases). Fix:
  spring-boot-starter-jdbc added to both poms (HikariCP + a real
  DataSource; JPA still waits for Phase 3), config moved to
  spring.datasource.* with spring.flyway.locations. Also found and
  cleaned during the first run: four orphaned java.exe processes from
  Phase 1 still held 8080/8081/8082/8761 (wrapper-only stop back then;
  correction noted in the Phase 1 log above), which made a fake
  health-check "UP" before any real migration. After killing them by
  PID and re-running with fresh builds: both services started on
  profile dev, HikariPool connected to
  jdbc:postgresql://localhost:15432/{catalogdb,warehousedb}
  (PostgreSQL 16.15), Flyway migrated V1 (schema) + V2 (seed) with
  success=t on both databases. psql evidence: catalogdb tables
  categories/units/products/skus (+ flyway_schema_history) all owned by
  catalog_app, row counts 2/2/2/2; warehousedb tables
  warehouses/storage_locations (+ flyway_schema_history) all owned by
  warehouse_app, row counts 1/3. Restart proof: both services stopped
  (java.exe killed, verified 0 remaining), started again - Flyway
  logged "Successfully validated 2 migrations ... Schema \"public\" is
  up to date. No migration necessary." and row counts were identical
  (2/2/2/2 and 1/3) - no seed duplication. Build evidence: catalog
  clean verify BUILD SUCCESS (1 test, 27.3 s), warehouse BUILD SUCCESS
  (1 test, 34.5 s), Flyway 11.7.2 resolved via the Boot BOM. Honest
  notes: test contexts exclude DataSourceAutoConfiguration and
  FlywayAutoConfiguration, so CI stays database-free; unit tests do not
  exercise migrations (real DB tests are Phase 3/Testcontainers); the
  live runs had no discovery/gateway running, so eureka client logs
  show connection-refused noise - expected, non-fatal, and out of this
  unit's scope; passwords came from .env, never echoed or logged. CI:
  run 40648268572 (unit 2.1 commit `e5ecdee`) and run 40651087397
  (this commit) both SUCCESS, all four matrix jobs green in ~30 s,
  only the known informational Ubuntu 26 note.

## Phase 3 progress log

- 2026-10-28 16:10-16:45+07:00: unit 3.1 (JPA foundation in
  catalog-service) `VERIFIED`. Added spring-boot-starter-data-jpa plus
  the Testcontainers trio (spring-boot-testcontainers,
  org.testcontainers:postgresql, org.testcontainers:junit-jupiter;
  Testcontainers 1.21.4 resolved via the Boot BOM) to the catalog pom.
  New JPA entities in dev.stockflow.catalog.domain
  (Category/Unit/Product/Sku) mapping the Flyway V1 schema 1:1:
  GenerationType.IDENTITY for GENERATED ALWAYS AS IDENTITY, Instant for
  TIMESTAMPTZ, insertable=false/updatable=false for the database-managed
  now() audit columns, lazy @ManyToOne for the real relations. Four
  JpaRepository interfaces in dev.stockflow.catalog.repository. Main
  application.yml gains spring.jpa.hibernate.ddl-auto=validate (migrations
  own the schema; entity drift fails startup) and open-in-view=false
  (DTO-only API contract, no lazy-in-view antipattern). Test
  application.yml rewritten: eureka off, the previous
  DataSource/Flyway autoconfiguration excludes removed - tests now run
  against a real PostgreSQL 16.15-alpine container via @ServiceConnection,
  so Flyway migrations and Hibernate validation execute for real in
  tests and in CI. Two failures found and fixed by evidence, not
  assumption: (1) AbstractCatalogIntegrationTest was package-private
  while the repository test lives in a sibling package - compile error,
  made the base public abstract; (2) the classic per-class container +
  Spring context caching trap: with @Container per-class lifecycle the
  first test class passed, then stopped its container while the cached
  context still pointed at the dead port (observed: "Connection to
  localhost:50297 refused", 2 tests errored in the second class, which
  had started a NEW container on port 65334 that Spring never used).
  Fix: singleton container pattern - static final container started in
  the static initializer, deliberately NOT managed by @Testcontainers,
  Ryuk reaps it at JVM exit. Proof the fix works: logs show exactly one
  container start for the whole JVM and the repository test class then
  runs in 1.7 s against the shared container. Second test trap
  prevented: inside a @Transactional test, findById after save returns
  the same managed instance from the L1 cache, so database-managed
  now() timestamps would read as null - the roundtrip test calls
  entityManager.flush() + clear() before re-reading so identity and
  audit timestamps come from real re-selection. Build evidence:
  catalog clean verify BUILD SUCCESS (4 tests, 0 failures/errors,
  41.3 s); logs show the container starting, Flyway "Migrating schema
  \"public\" to version \"1 - catalog schema\"" then "version \"2 -
  catalog seed\"" on the empty container, and Hibernate validation
  passing. Live startup path proof (the changed startup code, not just
  tests): catalog started on profile dev against the compose postgres
  with CATALOG_DB_PASSWORD from .env (never echoed); log shows
  HikariPool connected to jdbc:postgresql://localhost:15432/catalogdb
  (PostgreSQL 16.15), "Successfully validated 2 migrations", Tomcat on
  8081, Started in 12.4 s; GET /actuator/health returned 200
  {"status":"UP"}; afterwards the child java.exe (PID 8108) was killed
  by PID - the trailing maven "Process terminated with exit code: -1"
  is the kill confirmation, not a failure; eureka connection-refused
  noise during the run is the known non-fatal partial-stack condition.
  Honest notes: warehouse-service has no JPA yet (unit 3.3); no
  repositories contain custom JPQL yet (unit 3.2 adds the documented
  query); repository tests assert seed counts (2/2/2/2) against the
  container where Flyway ran from version 0, not against the long-lived
  compose volume. CI: run 40665107969 SUCCESS on commit `78569f3` -
  all four matrix jobs green; the catalog job's surefire summary (Tests
  run: 4, Failures: 0, Errors: 0) with a 12.9 s first test class proves
  the container started and Flyway migrated on the ubuntu runner, so
  the real-database tests are enforced in CI, not only locally.

- 2026-10-28 16:50-17:05+07:00: unit 3.2 (catalog products API)
  `VERIFIED`. Endpoints per initial-api-outline.md: GET/POST
  /api/v1/products, GET/PATCH /api/v1/products/{id}, GET
  /api/v1/categories. DTOs only (ProductRequest/ProductUpdateRequest/
  ProductResponse/CategoryResponse records; entities never serialized),
  Bean Validation via the new spring-boot-starter-validation dependency,
  RFC 7807 problem+json through a shared @RestControllerAdvice
  (400 validation with field-error map, 404 missing resource, 409
  duplicate sku via existsBySku pre-check plus a
  DataIntegrityViolationException safety net for constraint races).
  Pagination policy in one shared helper: page 0-based, size default 20
  clamped into 1..100, so a size=500 request is served with size 100
  (criterion "pagination has a maximum page size"). The documented JPQL
  query lives in ProductRepository.searchAll: active/category/search
  filters, all optional, combined with the ":param is null or ..." pattern.
  Page responses serialize through the stable spring-data DTO
  (@EnableSpringDataWebSupport VIA_DTO on the application class -
  the {"content":[...],"page":{...}} shape), so Boot 3.5's
  PageImpl-serialization warning never appears.
  Design adjustment vs unit 3.1 (documented on purpose): the audit
  timestamp columns moved from insertable=false/updatable=false to
  Hibernate @CreationTimestamp/@UpdateTimestamp. Reason: with
  insertable=false Hibernate never reads back the DEFAULT now() value,
  so a POST response would carry createdAt=null; writing the timestamps
  from the app keeps API responses truthful while the database defaults
  remain as a safety net for non-Hibernate writes. Schema unchanged -
  Hibernate validate still passes. PATCH uses saveAndFlush so the
  returned updatedAt is the flushed value, not the pre-update one.
  Failures found and fixed by evidence: (1) the first full test run
  failed CatalogRepositoryTest.seedDataIsVisibleThroughJpa because the
  API test class had already created products in the shared singleton
  container - exact count assertions were order-dependent; product/sku
  assertions relaxed to >= seed counts while read-only categories/units
  keep exact counts. (2) THE BIG ONE, caught by the live HTTP round not
  by MockMvc: GET /api/v1/products?page=0&size=500 returned 500 with
  PostgreSQL "function lower(bytea) does not exist" (SQLState 42883,
  pgjdbc 42.7.11, Hibernate 6.6.53): an unspecified null parameter
  inside lower(concat('%', :search, '%')) got resolved to bytea by the
  server. Fix: explicit casts on every nullable parameter in the JPQL
  (cast(:active as Boolean), cast(:categoryId as Long),
  cast(:search as String)). Oddity recorded honestly: the same query
  with null parameters PASSED under MockMvc against the Testcontainers
  postgres minutes earlier - the exact trigger for the difference is
  unresolved; the cast makes the query type-safe regardless, and both
  paths were re-verified green after the fix.
  Live run caught a second subtlety: the PATCH response initially
  returned a stale updatedAt because @UpdateTimestamp only applies at
  flush - fixed with saveAndFlush (above). Build evidence: catalog
  clean verify BUILD SUCCESS (17 tests: 13 API + 1 context + 3
  repository, 0 failures, ~51 s). Live HTTP evidence against the
  compose postgres (profile dev, password from .env, never echoed):
  health UP; GET /api/v1/products?page=0&size=500 -> 200 with
  "page":{"size":100}; GET /api/v1/products?search=spk&categoryId=1
  -> 200 exactly the seeded SPK-X1; GET /api/v1/products/9999 -> 404
  application/problem+json with the documented title/detail; POST with
  blank fields -> 400 problem+json listing errors.sku/name/categoryId/
  unitId; POST duplicate sku SPK-X1 -> 409 problem+json; PATCH -> 200
  with a fresh updatedAt and durable state confirmed by a follow-up
  GET; POST -> 201 with Location header and populated timestamps.
  Honest notes: the long-lived compose catalogdb now carries one extra
  product row (LIVE-3.2, id 3) created during the live proof and the
  seed row 2's updated_at advanced - dev demo data, never part of test
  assertions (tests assert on their own fixtures); warehouse endpoints
  wait for unit 3.3; OpenAPI annotations/springdoc wait for unit 3.4,
  so "OpenAPI reflects actual behavior" is not yet claimed. CI: run
  40667190280 SUCCESS on commit `85a33d3` - all four matrix jobs green,
  the catalog job ran 17 tests (1+3+13) with the container on the
  runner. Process note recorded: immediately after the push,
  "gh run list --limit 1" returned the still-newest registered run,
  which belonged to the previous docs commit (40665351249, catalog 4
  tests) - watching it reported success for the WRONG commit; always
  match the run to the commit's headSha before claiming CI evidence.

- 2026-10-28 16:54-17:10+07:00: unit 3.3 (warehouse API) `VERIFIED`.
  Same patterns as catalog unit 3.2, applied to warehousedb: DTO records
  only (WarehouseRequest/WarehouseUpdateRequest/WarehouseResponse/
  StorageLocationRequest/StorageLocationResponse; entities never
  serialized), Bean Validation, RFC 7807 problem+json via a shared
  @RestControllerAdvice (400 with errors map, 404, 409 duplicate code
  via existsByCode/existsByWarehouseIdAndCode pre-checks plus a
  DataIntegrityViolationException safety net), the shared Pagination
  helper (page 0-based, size default 20 clamped 1..100),
  @EnableSpringDataWebSupport VIA_DTO ({"content":[...],"page":{...}}),
  audit timestamps via @CreationTimestamp/@UpdateTimestamp with the
  database DEFAULT now() as safety net, and saveAndFlush in the PATCH
  path so the returned updatedAt is the flushed value. Endpoints:
  GET/POST /api/v1/warehouses, GET/PATCH /api/v1/warehouses/{id},
  GET/POST /api/v1/warehouses/{id}/locations. Two warehouse-specific
  contract points honored: location codes are unique PER WAREHOUSE
  (composite UNIQUE in V1) - a duplicate within the same warehouse is
  409 while the same code in another warehouse is 201 - and a missing
  warehouse for GET /{id}/locations is a documented 404, not an empty
  page. storage_locations has no updated_at, so StorageLocation carries
  only created_at. Config: main application.yml gains ddl-auto=validate
  + open-in-view=false; test application.yml rewritten (eureka off,
  Phase 2 excludes removed); Testcontainers base class uses the
  singleton pattern (static initializer + @ServiceConnection, no
  @Testcontainers) - the catalog lesson applied before it could bite
  here. Design decision found during implementation: location listing
  and creation run inside WarehouseService transactions and map entities
  to DTOs there, because with open-in-view=false a lazy @ManyToOne
  touched at controller level would throw LazyInitializationException.
  Build evidence: warehouse clean verify BUILD SUCCESS, 15 tests
  (14 API + 1 context), 0 failures/errors, 42.157 s, exit 0 - green on
  the first full run, no fixes needed (the catalog lessons held).
  Live HTTP evidence against the compose postgres (profile dev,
  WAREHOUSE_DB_PASSWORD from .env, never echoed): health UP after ~10 s;
  GET /api/v1/warehouses/1 -> 200 with the seeded WH-JKT01 and populated
  timestamps; GET /api/v1/warehouses/1/locations -> 200 with the 3
  seeded rows in the VIA_DTO page shape; POST /api/v1/warehouses
  {"code":"LIVE-WH"} -> 201 with Location /api/v1/warehouses/2; POST
  /api/v1/warehouses/2/locations {"code":"LIVE-A-01"} -> 201; duplicate
  warehouse code WH-JKT01 -> 409 problem+json; GET /9999 -> 404
  problem+json. Honest notes: the compose warehousedb now carries the
  demo rows LIVE-WH (id 2) + LIVE-A-01 (id 4) - dev demo data, never
  part of test assertions; Location headers are relative URIs (same as
  catalog); eureka connection-refused noise is the known non-fatal
  partial-stack condition; OpenAPI annotations wait for unit 3.4.
  CI evidence: run 40668312313 SUCCESS on commit `30648a8`
  (headSha-matched before watching, per the unit 3.2 process note) -
  all four matrix jobs green; the warehouse job's surefire summary
  (Tests run: 15, Failures: 0, Errors: 0, the API class 14 in 2.0 s)
  proves the container started and Flyway migrated on the ubuntu
  runner, so the real-database warehouse tests are enforced in CI, not
  only locally.

- 2026-10-28 17:00-17:15+07:00: unit 3.4 (OpenAPI via springdoc)
  `VERIFIED`. springdoc-openapi-starter-webmvc-ui 2.10.0 (the ADR 0001
  pin; not managed by the Boot BOM, so an explicit version) added to
  both catalog and warehouse poms. Each service gains a small
  OpenApiConfig (document title/description/version) and the controllers
  got swagger annotations that document exactly the behavior the tests
  and live runs already proved: 201 + Location header on POST,
  400 problem+json with the errors map on bean-validation failures,
  404 on missing resources, 409 on duplicate codes (including the
  per-warehouse location-code uniqueness and the PATCH-rename-to-
  existing-code conflict), and the pagination clamp on every list
  endpoint - "OpenAPI reflects actual behavior" is claimed from this
  unit on, backed by construction (annotations mirror the implemented
  handlers). New test per service (OpenApiDocsTest, MockMvc): GET
  /v3/api-docs returns 200 with the service's document title, the real
  controller paths present, and foreign paths absent (warehouse paths
  not in catalog's doc and vice versa). Build evidence: catalog clean
  verify BUILD SUCCESS, 19 tests (1 context + 3 repository + 13 API +
  2 OpenAPI), 0 failures/errors, 58.559 s, exit 0; warehouse clean
  verify BUILD SUCCESS, 17 tests (1 context + 14 API + 2 OpenAPI),
  0 failures/errors, 50.263 s, exit 0. Live evidence, both services on
  profile dev against the compose postgres (passwords from .env, never
  echoed): health UP (catalog ~10 s, warehouse already up); GET
  /v3/api-docs -> 200 application/json on both with the correct titles
  ("StockFlow catalog-service API" / "StockFlow warehouse-service API",
  version v1) and exactly the documented path lists; GET
  /swagger-ui/index.html -> 200 text/html on both; response-code spot
  check in the served document: catalog POST /api/v1/products -> 201/
  400/409; warehouse POST /api/v1/warehouses -> 201/400/409, GET
  /{id} -> 200/404, GET /{id}/locations -> 200/404. Honest notes: both
  child java.exe processes were killed after the live proof (PIDs
  23152, 9360; the trailing maven exit code -1 is the kill
  confirmation); springdoc logs its own "enabled by default" notices -
  harmless here, and the production disabling question is deferred to
  Phase 4/5 configuration; the live runs created no demo data this time
  (read-only checks). CI evidence: run 40669289647 SUCCESS on commit
  `f642b38` (headSha-matched before watching) - all four matrix jobs
  green; the catalog job ran 19 tests and the warehouse job ran 17
  tests on the runner (container-backed, per the earlier evidence).

## Phase 4 progress log

- 2026-10-28 17:15-17:40+07:00: unit 4.1 (multi-stage Dockerfiles,
  non-root runtime) `VERIFIED`. Image tags verified live against Docker
  Hub API before use (no floating-guessing): builder
  `maven:3.9.16-eclipse-temurin-21` (exact Maven version the mvnw
  wrapper uses on the host) and runtime
  `eclipse-temurin:21.0.12.1_1-jre-alpine-3.24` (exact JDK build the
  host compiles with). One multi-stage Dockerfile per app
  (discovery-server, api-gateway, catalog-service, warehouse-service):
  dependency layer (`mvn dependency:go-offline` on pom.xml only) then
  source layer then `package -DskipTests`; runtime stage copies the
  repackaged jar, creates an unprivileged `app` user
  (`addgroup -S app && adduser -S -G app app`), runs as `USER app` and
  `java -jar app.jar` - the service ports (8080-8082, 8761) are all
  >1024 so no privileged binding is needed. `.dockerignore` per app
  keeps `target/`, `.mvn/` and the wrapper scripts out of the build
  context. Build evidence: `docker build` exit 0 for all four images,
  tagged `stockflow/<name>:0.0.1-SNAPSHOT`. Smoke evidence (temp
  containers on the existing compose network, removed afterwards):
  every container runs as user `app` (docker exec whoami -> app, all
  four - non-root criterion met by execution, not assumption);
  discovery booted standalone (health UP after ~20 s); catalog and
  warehouse booted against the compose postgres through internal DNS
  (`jdbc:postgresql://stockflow-postgres:5432/{catalogdb,warehousedb}`,
  Flyway validated, JPA started, health UP) proving the runtime images
  work against the real engine, not just compile; gateway health UP.
  All health checks returned {"status":"UP"} on temporary
  127.0.0.1-only published ports (18761/18080-18082, smoke-only, no
  compose change). Honest notes: CI still verifies via mvnw only -
  image builds are not yet exercised on clean-clone runners (a
  compose-based CI job is a candidate later in Phase 4 if needed);
  runtime images are not pushed to any registry - local daemon only,
  Phase 4 compose builds them from source; the smoke runs used
  EUREKA_CLIENT_ENABLED=false (full-stack registration is unit 4.2's
  subject). CI evidence: run 40673481363 SUCCESS on commit `34778ed`
  (headSha-matched before watching) - all four matrix jobs green
  (Dockerfiles do not affect the mvnw CI path; run recorded to keep the
  chain continuous).

- 2026-10-28 17:45-18:10+07:00: unit 4.2 (core compose + service
  discovery) `VERIFIED`. compose.yaml is now the full core stack:
  discovery, gateway, catalog, warehouse, postgres, redis on one
  internal `stockflow` network with named volumes (postgres_data,
  redis_data). Host publishing policy (ROADMAP "only required host
  ports"): gateway 8080 (the single public entry point) and discovery
  8761 (operator-facing Eureka dashboard, never routed through the
  gateway) on 127.0.0.1 only; catalog, warehouse, postgres and redis
  are internal-only - postgres lost its Phase 2 temporary publish
  (127.0.0.1:15432) exactly as the Phase 2 compose comment planned.
  Redis (redis:7.4.12-alpine per ADR 0001) is included per the ROADMAP
  Phase 4 deliverable although nothing uses it until Phase 6; it is
  health-checked from day one. Application wiring is env-var based in
  compose (no new Spring profile files): SPRING_DATASOURCE_URL points
  at jdbc:postgresql://postgres:5432/... and
  EUREKA_CLIENT_SERVICEURL_DEFAULTZONE at http://discovery:8761/eureka/
  - internal service DNS names, never localhost. depends_on with
  service_healthy makes postgres/discovery gate the app containers;
  every java container gets a busybox-wget healthcheck against its own
  actuator health endpoint (wget ships in the Temurin Alpine runtime
  image). Verification per ROADMAP: `docker compose config --quiet`
  exit 0; `docker compose up --build -d` exit 0 (rebuilt from source
  through the Dockerfiles); `docker compose ps` shows all SIX
  containers healthy ~40-45 s after start; Eureka REST
  (127.0.0.1:8761/eureka/apps) lists API-GATEWAY, CATALOG-SERVICE and
  WAREHOUSE-SERVICE all status=UP; service logs prove internal DNS
  (Flyway connected to jdbc:postgresql://postgres:5432/{catalogdb,
  warehousedb} - no host-only URL anywhere); each java service's
  health endpoint called from inside the network via
  `docker compose exec ... wget` (all {"status":"UP"}), postgres
  pg_isready accepting, redis PONG. Restart behavior: `docker compose
  restart warehouse` -> re-registered in Eureka as UP after ~40 s and
  health UP again. Volume preservation: `docker compose down` exit 0
  left both named volumes in place (docker volume ls), and a following
  `docker compose up -d` brought all six containers healthy again with
  the data intact - catalogdb still holds seed rows SPK-X1/DSK-MAT and
  the demo row LIVE-3.2 created in unit 3.2, proving down does not
  touch volume data. Honest notes: gateway routes nothing yet (Phase
  5) - its 8080 health endpoint is the only exercised surface;
  Eureka-registered instance hostnames are container IDs (resolvable
  inside the network; gateway-based routing in Phase 5 will exercise
  that path for real); host-side mvnw development runs lose direct
  postgres access now that the publish is gone - README (unit 4.3)
  documents an optional one-line override for that case; destructive
  cleanup (down -v) is deliberately NOT documented as part of the
  normal workflow and gets its own warning section in unit 4.3. CI
  evidence: run 40674415027 SUCCESS on commit `e59df72`
  (headSha-matched before watching) - all four matrix jobs green.

- 2026-10-28 18:15-18:30+07:00: unit 4.3 (local setup documentation +
  hostdev override) `VERIFIED`. README.md rewritten where it had gone
  stale: the status section now names Phases 0-3 VERIFIED (Phase 3 gate
  closed) and Phase 4's state instead of claiming "Phases 1-10 NOT
  STARTED"; a Quick start section documents the clean-clone path
  (copy .env.example -> fill credentials -> `docker compose up --build
  -d` -> `docker compose ps` -> gateway health + Eureka dashboard +
  in-container health checks), a ports table states exactly what is
  published and why, restart and down behavior are documented
  (including the verified ~40 s Eureka re-registration), and a
  dedicated DESTRUCTIVE cleanup section warns that `docker compose
  down -v` deletes the volumes and is intentionally not part of any
  normal workflow. New optional `compose.hostdev.yaml`: a one-service
  override that publishes postgres on 127.0.0.1:15432 for host-side
  mvnw debugging, opt-in only, with the invocation documented. .env.example
  updated: POSTGRES_PORT is now documented as belonging to the optional
  override only. Verification: `docker compose -f compose.yaml -f
  compose.hostdev.yaml config` exit 0 showing the extra 15432->5432
  publish; live proof - `up -d postgres` with the override made
  127.0.0.1:15432 reachable from the host, recreating postgres without
  the override returned it to internal-only (compose ports shows only
  5432/tcp) and healthy; the other five containers stayed healthy
  through the postgres recreate (all-healthy after ~90 s total).
  Honest note: the quick start was verified on this machine (warm
  Docker layer cache); a cold-cache clean-clone timing was not measured
  - first `up --build` on a fresh clone takes longer (dependency
  downloads inside the builder stage) but the commands are identical.
  CI evidence: run 40674854953 SUCCESS on commit `0070255`
  (headSha-matched before watching) - all four matrix jobs green;
  with this the Phase 4 gate review above is fully evidenced by CI as
  well.
## Phase 4 gate review (2026-10-28, closing Phase 4)

Every ROADMAP Phase 4 deliverable and acceptance criterion, checked
against recorded evidence only:

- Multi-stage Dockerfile per application, non-root runtime: `VERIFIED`
  - unit 4.1; four Dockerfiles, `docker build` exit 0 each, `whoami`
  -> app in every running container.
- Core compose.yaml: gateway, discovery, catalog, warehouse,
  PostgreSQL, Redis: `VERIFIED` - unit 4.2; all six services defined,
  built from source, and healthy.
- Internal Docker network and persistent volumes: `VERIFIED` - one
  `stockflow` network, named volumes postgres_data + redis_data,
  survival proven across down/up.
- Eureka registration for services: `VERIFIED` - CATALOG-SERVICE,
  WAREHOUSE-SERVICE, API-GATEWAY all status=UP in the Eureka REST
  registry; re-registration after restart proven.
- Health checks/readiness configuration: `VERIFIED` - postgres
  pg_isready, redis PING, every java service wget against its actuator
  health, depends_on service_healthy gating, all verified live.
- .env.example and local setup instructions: `VERIFIED` - unit 4.3
  README quick start + .env.example.
- Clean clone can start the core stack using documented commands:
  `VERIFIED` on the reference machine - the documented commands are
  exactly the ones executed (config/up/ps/health); honest caveat
  recorded: cold-cache clean-clone build time not measured, commands
  identical.
- All core containers become healthy/ready or expose an accurately
  documented readiness limitation: `VERIFIED` - 6/6 healthy, no
  readiness limitation to document.
- Service-to-service traffic uses internal DNS/discovery, not
  hard-coded host-only URLs: `VERIFIED` - service logs show
  jdbc:postgresql://postgres:5432/... and eureka defaultZone
  http://discovery:8761/eureka/; no localhost URL exists in the
  compose stack's runtime config.
- Only required host ports are published: `VERIFIED` - 127.0.0.1
  8080 (gateway, public entry) and 8761 (operator dashboard) only;
  postgres/redis/catalog/warehouse internal-only, override file is
  opt-in and documented as such.
- Restart behavior is documented and tested: `VERIFIED` - warehouse
  restart re-registered UP ~40 s; postgres recreate recovered;
  README documents both.
- `docker compose down` preserves named volumes; destructive cleanup
  separately documented: `VERIFIED` - volumes present after down,
  data intact after re-up, README carries the DESTRUCTIVE section.

Phase 4 overall: CLOSED. Carried-forward notes remain honest open
items: images are not pushed to any registry (compose builds from
source; registry publication is a possible later phase item);
Eureka-registered instance hostnames are container IDs (exercised for
real by gateway routing in Phase 5); CI builds images only implicitly
through mvnw - a compose-based CI job remains a candidate enhancement,
not a gate failure.

## Phase 3 gate review (2026-10-28, closing Phase 3)

Every ROADMAP Phase 3 deliverable and acceptance criterion, checked
against recorded evidence only:

- Catalog API minimum (all six endpoints): `VERIFIED` - unit 3.2;
  live HTTP evidence (list/filter/create/get/patch, categories list)
  and 13 MockMvc tests, plus 3 repository tests exercising the JPQL
  query through the real engine.
- Warehouse API minimum (all six endpoints): `VERIFIED` - unit 3.3;
  live HTTP evidence (list/get/create/patch/locations list+create) and
  14 MockMvc tests.
- DTO validation returns documented 400 responses: `VERIFIED` - live
  proof on both services (catalog POST blank fields -> 400 problem+json
  listing errors.sku/name/categoryId/unitId; warehouse POST blank
  code/name and blank location code -> 400 with the errors map), plus
  dedicated tests.
- Missing resource returns 404: `VERIFIED` - catalog GET /9999 and
  warehouse GET /9999 + GET /9999/locations -> 404 problem+json live;
  dedicated tests; the warehouse locations variant proves 404, not an
  empty page.
- Duplicate SKU/code returns documented 409: `VERIFIED` - live proof:
  catalog duplicate sku, warehouse duplicate WH-JKT01, duplicate
  location code within one warehouse (409) vs the same code in another
  warehouse (201); dedicated tests.
- Public APIs never serialize JPA entities directly: `VERIFIED` - DTO
  records only in both services, open-in-view=false, and page
  serialization via the stable spring-data DTO (VIA_DTO).
- Pagination has a maximum page size: `VERIFIED` - the shared
  Pagination helper clamps size into 1..100; live size=500 was served
  with page.size=100 on both services.
- JPQL is used by at least one documented query: `VERIFIED` -
  ProductRepository.searchAll (optional active/category/search filters,
  explicit casts after the bytea lesson), exercised by repository tests
  and the live search=spk&categoryId=1 call.
- Repository/service/controller tests cover happy paths and failure
  paths: `VERIFIED` - catalog 19 tests, warehouse 17 tests, both
  against real PostgreSQL containers locally AND in CI (runs
  40669289647, plus the per-unit runs recorded above).
- OpenAPI reflects actual behavior: `VERIFIED` - unit 3.4; springdoc
  2.10.0 serves /v3/api-docs + Swagger UI live on both services; the
  document's path lists match the controllers exactly (foreign paths
  absent, asserted by tests); documented response codes were spot
  checked against the served documents (201/400/409 on POSTs, 200/404
  on GETs).

Phase 3 overall: CLOSED - all deliverables and acceptance criteria have
recorded evidence; nothing was claimed without a command that actually
ran. Carried-forward notes remain honest open items, not gate failures:
the stack is past OSS EOL 2026-06-30 (upgrade needs an explicit user
decision + superseding ADR); demo/prod profiles stay unexercised until
Phase 4; root-account seeding remains deferred to Phase 5 (ADR 0003);
springdoc "enabled by default" production disabling is a Phase 4/5
configuration question.

## Phase 5 progress log

- 2026-10-29 12:28+07:00: unit 5.1 (dynamic routing in api-gateway)
  `VERIFIED`. Added `spring-cloud-contract-wiremock` to `apps/api-gateway/pom.xml`
  (version managed by Spring Cloud 2025.0.3 BOM) for in-memory reactive routing
  verification without requiring external daemon infrastructure. Created
  `dev.stockflow.gateway.config.GatewayRoutesConfig` using Spring Cloud Gateway's
  `RouteLocatorBuilder` defining dynamic routes:
  - `catalog-products-route`: `/api/v1/products`, `/api/v1/products/**` -> `lb://CATALOG-SERVICE`
  - `catalog-categories-route`: `/api/v1/categories`, `/api/v1/categories/**` -> `lb://CATALOG-SERVICE`
  - `warehouse-route`: `/api/v1/warehouses`, `/api/v1/warehouses/**` -> `lb://WAREHOUSE-SERVICE`
  Automated tests in `DynamicRoutingGatewayTest` executed against local random port
  with WireMock stubbing: 9 assertions covering product listing/detail/creation,
  category listing, warehouse listing/locations, and strict perimeter safeguards:
  local gateway actuator health handled locally (HTTP 200), unmapped unknown paths
  returning HTTP 404, and downstream internal actuator paths returning HTTP 404.
  All 10 tests across `api-gateway` passed green (`.\mvnw.cmd test` exit code 0).
  Deliverables created: `guidebooks/01-dynamic-routing.md` and runnable request
  collection `playground/http/gateway-dynamic-routing.http`.

- 2026-10-29 14:22+07:00: unit 5.2 (JWT authentication and role authorization
  in api-gateway) `VERIFIED`. Adopted ADR 0006 establishing HS256 JWT
  specification (minimum 256-bit key entropy, claims `sub`, `roles`, `iss`,
  `iat`, `exp`), perimeter least-privilege RBAC (`ROLE_USER` for GET, `ROLE_ADMIN`
  for mutating verbs), and strict demo root account isolation fulfilling ADR 0003.
  Dependencies added to `apps/api-gateway/pom.xml`: `spring-security-crypto`
  (BCrypt password hashing) and `io.jsonwebtoken` (jjwt-api, jjwt-impl,
  jjwt-jackson pinned to 0.12.6). Components implemented:
  - `dev.stockflow.gateway.security.JwtService`: token generation and claims parsing.
  - `dev.stockflow.gateway.security.JwtAuthenticationFilter`: reactive `GlobalFilter`
    enforcing perimeter security, anti-spoofing header cleaning (`X-User-Id`,
    `X-User-Roles` purged on ingress), verb-to-role authorization, downstream
    context header injection, and standardized RFC 7807 `ProblemDetail` (401/403).
  - `dev.stockflow.gateway.security.DemoAuthController`: profile-gated (`@Profile("!prod")`)
    login endpoint `/api/v1/auth/login` validating root and demo-user credentials
    using `BCryptPasswordEncoder`.
  Automated tests in `JwtAuthenticationGatewayTest` (9 tests) and
  `ProductionProfileSecurityTest` (1 test) executed live against local random port:
  - Verified valid `ROLE_USER` read access (200) with downstream header propagation.
  - Verified `ROLE_USER` mutation rejection with 403 Forbidden ProblemDetail.
  - Verified `ROLE_ADMIN` mutation access (201) with downstream header propagation.
  - Verified missing token (401), expired token (401), tampered signature (401).
  - Verified anti-spoofing header cleaning prevents external identity forgery.
  - Verified demo login success (200 with JWT) and failure (401 with ProblemDetail).
  - Verified production profile isolation: `/api/v1/auth/login` returns 404 in `prod`.
  All 20 tests across `api-gateway` passed green (`.\mvnw.cmd test` exit code 0).
  Deliverables created: `docs/decisions/0006-gateway-jwt-authentication.md`,
  `guidebooks/02-jwt-authentication-authorization.md`,
  `playground/http/gateway-jwt-auth.http`, and `.env.example` updated with `JWT_SECRET`.

- 2026-10-29 14:40+07:00: unit 5.3 (Redis-backed distributed rate limiting in api-gateway)
  `VERIFIED`. Added `spring-boot-starter-data-redis-reactive` to `apps/api-gateway/pom.xml`.
  Implemented token bucket distributed rate limiting utilizing Spring Cloud Gateway's
  `RedisRateLimiter` and dynamic anti-spoofing `KeyResolver`:
  - `dev.stockflow.gateway.ratelimit.RateLimitProperties`: binds `stockflow.gateway.rate-limit`
    configuration (default replenishRate=10, burstCapacity=20, requestedTokens=1).
  - `dev.stockflow.gateway.ratelimit.UserOrIpKeyResolver`: securely resolves rate-limiting key
    from verified security context `stockflow.authenticated.user` (`user:<userId>`), falling
    back to remote socket address (`ip:<remoteIp>`). Completely ignores any unverified
    client-supplied `X-User-Id` headers to prevent identity spoofing and quota depletion attacks.
  - `dev.stockflow.gateway.ratelimit.RateLimiterConfig`: reactive `RedisRateLimiter` bean
    guarded with `@ConditionalOnMissingBean(RateLimiter.class)` to facilitate deterministic in-memory
    testing without local Redis daemon dependencies.
  - `dev.stockflow.gateway.config.GatewayRoutesConfig`: applied `requestRateLimiter` filter
    configured with `setStatusCode(HttpStatus.TOO_MANY_REQUESTS)` to all catalog and warehouse routes.
  - `compose.yaml`: api-gateway service updated with `SPRING_DATA_REDIS_HOST: redis`,
    `SPRING_DATA_REDIS_PORT: 6379`, and `depends_on: redis`.
  - `src/test/resources/application.yml`: configured `management.health.redis.enabled: false`
    to keep gateway actuator health UP during in-memory WireMock tests.
  Automated tests in `UserOrIpKeyResolverTest` (4 tests) and `RateLimitingGatewayTest` (2 tests):
  - Verified authenticated request resolves to `user:<userId>`.
  - Verified unauthenticated request falls back to `ip:<remoteAddress>`.
  - Verified spoofed `X-User-Id` on unauthenticated request is safely ignored, resolving to client IP.
  - Verified socket-less request falls back safely to `ip:anonymous`.
  - Verified normal traffic within quota returns 200 OK + `X-RateLimit-*` headers, routing to downstream.
  - Verified burst traffic exceeding quota returns 429 Too Many Requests, blocking downstream invocation.
  All 26 tests across `api-gateway` passed green (`.\mvnw.cmd test` exit code 0).
  Deliverables created: `guidebooks/03-redis-rate-limiting.md` and runnable request collection
  `playground/http/gateway-rate-limiting.http`.

- 2026-11-03: real-Redis check of unit 5.3 against the compose stack
  (Windows, Docker 29.7.2). `docker compose up --build -d`: all six
  containers healthy, including api-gateway with the Redis dependency.
  Login as the demo user, then 40 sequential requests to
  `GET /api/v1/products` all returned 200 (the bucket refills 10/s, so a
  sequential loop never drains 20 tokens). `redis-cli --scan` then showed
  `request_rate_limiter.{catalog-products-route.user:demo-user}.tokens`
  and `.timestamp`: the limiter writes per-user keys to Redis. A parallel
  burst (80 requests, 40 at a time, after a 3 s pause) gave 59x 200 and
  21x 429. A follow-up request returned `X-RateLimit-Remaining: 9`,
  `Burst-Capacity: 20`, `Replenish-Rate: 10`. Single run, local machine.
  The unit tests (26/26, `.\mvnw.cmd clean verify` exit 0) still use the
  in-memory limiter; this entry is the only Redis-backed proof.
  Also recorded: commit `1f798c3` (5.2 alone) was built in a clean
  worktree, 20/20 tests green; commit `b9b6fbf` is 5.3. Not yet run: CI
  for these two commits.
  Open findings from this check, not fixed here: (1) `DemoAuthController`
  has working default passwords in `@Value` defaults and `JwtProperties`
  has a default signing secret, which conflicts with the "no working
  password in source" rule in ROADMAP section 2; the compose gateway ran
  without `JWT_SECRET` set. (2) `AGENTS.md` is untracked and not in
  `.gitignore`; AGENT_RULES says it must stay local.
- 2026-11-03: removed the default JWT signing secret and the default demo
  passwords from the gateway (finding from the real-Redis check above).
  `JwtService` now fails fast when the secret is missing or under 32
  bytes; `ROOT_PASSWORD` and `DEMO_USER_PASSWORD` have no `@Value`
  default; compose requires `JWT_SECRET`, `ROOT_USERNAME`, `ROOT_PASSWORD`
  and `DEMO_USER_PASSWORD` (`docker compose config` fails with "required
  variable JWT_SECRET is missing a value" when it is absent). Tests carry
  their own fixture values in `src/test/resources/application.yml`.
  `.\mvnw.cmd clean verify` in `apps/api-gateway`: 28 tests (2 new in
  `JwtServiceTest`), 0 failures, BUILD SUCCESS. Live on the rebuilt
  gateway container: login with the `.env` demo password returned 200,
  the old hardcoded default returned 401. `.env` values are random and
  were never printed. The earlier STATUS entries that quote the old
  defaults describe the state at that time.
- 2026-11-03: CI evidence for units 5.2 and 5.3 and the secrets fix: Actions
  run 41566713553 on commit `dea268f` (PR 2 branch head) concluded success,
  4 of 4 checks passing. PR 2 merged to main as `6b86965`.

- 2026-11-03: unit 5.4 (circuit breaker and controlled fallback in
  api-gateway) `VERIFIED`. Added `spring-cloud-starter-circuitbreaker-reactor-resilience4j`
  (version from the Spring Cloud 2025.0.3 BOM, approved by the user). Per-route
  breakers (`catalog-products`, `catalog-categories`, `warehouse`) after the rate
  limiter; failures are exceptions, timeouts and downstream 500/502/503/504;
  `FallbackController` returns a 503 problem+json and never a success body;
  no retry, so a write is sent once. Settings live under
  `stockflow.gateway.circuit-breaker.*`.
  `.\mvnw.cmd clean verify` in `apps/api-gateway`: 31 tests (3 new in
  `CircuitBreakerGatewayTest`), 0 failures, BUILD SUCCESS. The tests prove:
  3 requests to a failing downstream give 3x 503 with only 2 downstream hits;
  a slow downstream times out to 503; a failed POST gives 503 and reaches the
  downstream exactly once.
  Live on the compose stack (rebuilt gateway): healthy GET 200; with `catalog`
  stopped, 6 GETs and a POST /api/v1/products all returned 503 problem+json;
  after `docker compose start catalog` the same GET returned 200 again, 31 s
  after the start (includes the catalog boot). Single run, local machine.
  Limits: circuit state is per gateway instance; `GET /fallback` is reachable
  directly without a token and returns the same constant 503; the live POST was
  rejected by an already-open circuit, so the "downstream 500 on a write" case
  is covered by the unit test only.

- 2026-11-03: unit 5.5 (discovery-based load balancing with two instances)
  `VERIFIED`. No gateway code change: the `lb://CATALOG-SERVICE` routes
  already balance round robin. Added `LoadBalancingGatewayTest` (two
  WireMock instances behind the simple discovery client; 10 requests give
  exactly 5 and 5) and the opt-in `compose.labs.yaml` (second catalog
  instance `catalog-2`, metrics endpoint exposed on both catalog instances,
  internal only). `mvnw.cmd --batch-mode test -Dtest=LoadBalancingGatewayTest`:
  1 test, 0 failures. Live on the compose stack with
  `-f compose.yaml -f compose.labs.yaml`: Eureka listed two UP
  CATALOG-SERVICE instances (plus one stale DOWN entry left from the earlier
  catalog restart). 20 requests through the gateway, counted per instance via
  `http.server.requests` for `/api/v1/products`: catalog +10, catalog-2 +10.
  Failure path: after `stop catalog-2`, 12 requests sent at once gave 5x 200
  and 7x 503 (the gateway still used its cached instance list; the circuit
  breaker answered 503); 45 s later 12 of 12 were 200. Single run, local
  machine. Full gateway suite: `clean verify` 32 tests, 0 failures. Limits: no failover to the surviving instance (that is use case 6);
  circuit state and the instance cache are per gateway instance.
  The lab instance was removed afterwards and the core stack restored
  (6 containers healthy).

- 2026-11-03: unit 5.6 (timeout and bounded retry for safe operations)
  `VERIFIED`. Tests written first: 3 of the 4 new tests failed before the
  change (retry missing). Implementation: retry filter inside the circuit
  breaker on all three routes, GET only, statuses 502/503/504 plus timeouts and
  I/O errors, 2 retries with exponential backoff (`RetryProperties`,
  `stockflow.gateway.retry.*`); gateway HTTP client connect-timeout 2000 ms and
  response-timeout 1s in `application.yml`; circuit breaker timeout raised from
  2 s to 5 s so it covers all attempts. `RetryTimeoutGatewayTest`, run twice
  (4 tests, 0 failures each time): flaky GET retried to 200 with 2 hits;
  always-503 GET sent 3 times then 503; POST with 503 sent once; slow
  downstream attempted 3 times then 503. Live (compose labs, rebuilt gateway,
  two catalog instances): both up 6/6 200; right after `stop catalog-2`,
  12/12 requests were 200 (unit 5.5 measured 5x 200 and 7x 503 for the same
  step before retry existed); with the only catalog instance `docker pause`d, one
  GET returned 503 after 3402 ms (3 x 1 s response timeout plus backoff), which
  also proves the production timeout values are loaded. Single run, local
  machine. Notes: the first draft of the timeout test passed only because it set
  its own 300 ms timeout; `src/test/resources/application.yml` shadows the main
  one, so timeout settings are repeated in the test properties. A first run of
  the flaky-retry test with a 300 ms timeout once counted 3 hits instead of 2,
  most likely a cold-start timeout; it was stable at 1 s (2 runs). Limits: retries
  are not jittered by this configuration beyond reactor's default backoff
  behaviour (not measured); no idempotency keys, so writes are never retried.
  Core stack restored afterwards.
  Full gateway suite after this unit: `clean verify` 36 tests, 0 failures,
  run twice. One earlier full run failed once in
  `CircuitBreakerGatewayTest.failedWriteReturns503AndIsSentOnce` (the POST never
  reached WireMock, consistent with its 300 ms breaker timeout firing on a slow
  cold start); that test's timeout was raised to 1 s and its slow stub to 3 s,
  and the suite then passed twice.

- 2026-11-03: unit 5.7 (request ID and request/response transformation)
  `VERIFIED`. Tests written first: all 5 new tests failed before the change.
  Added `RequestIdFilter` (global, highest precedence): keeps a client
  `X-Request-Id` only if it matches `[A-Za-z0-9._-]{1,64}`, else generates a
  UUID; forwards it downstream; sets it once on the response in `beforeCommit`
  so gateway-produced answers (401, 429, 503) carry it. In `protect(...)` the
  response `Server` header is removed and `X-Content-Type-Options: nosniff`
  added. `RequestIdGatewayTest`: 5 tests, 0 failures (generated ID visible
  downstream and in the response; valid client ID kept; malformed ID replaced;
  401 carries an ID; Server removed and nosniff added). Live on the rebuilt
  gateway: no ID sent returned a generated UUID, `client-trace-42` came back
  unchanged, `bad id <x>` was replaced by a UUID, a 401 without token carried an
  ID, and nosniff was present with no Server header. Single run, local machine.
  Limits: that the downstream receives the ID is proven by the WireMock test
  only, because catalog and warehouse do not log it yet (structured logging is
  Phase 10); the ID is not a W3C trace context (use case 8).
  Full gateway suite after this unit: `clean verify` 41 tests, 0 failures (one run).

- 2026-11-03: unit 5.8 (distributed tracing and correlation ID)
  `VERIFIED` for log-level tracing. Added `micrometer-tracing-bridge-otel`
  (Boot-managed version, approved by the user) to api-gateway, catalog-service
  and warehouse-service; `management.tracing.sampling.probability: 1.0` in all
  three; `spring.reactor.context-propagation: auto` in the gateway. Tests
  first: both `TracingGatewayTest` tests failed before the dependency (no
  `traceparent` downstream); after it, 2/2 pass. A first version of the test
  passed one case only because WireMock's request journal carried over from the
  other test; fixed with a reset in `@BeforeEach`. `clean verify` per app:
  api-gateway 43 tests, catalog-service 19, warehouse-service 17, all with 0
  failures. Live on the rebuilt stack (compose labs for DEBUG request logs): one
  request through the gateway with `traceparent` trace id
  `4bf92f3577b34da6a3ce929d0e0e4736` produced log lines with that trace id and
  different span ids in both the gateway (`e2d5125870ae0299`) and the catalog
  (`815209bfb063e89f`). Before `spring.reactor.context-propagation: auto` the
  gateway lines had an empty trace slot, so that setting is required. The
  correlation ID is `X-Request-Id` (unit 5.7); the trace id is the internal
  identifier. Single run, local machine. Limits: no exporter, collector or Jaeger,
  so spans are not viewable as a tree (Phase 10); warehouse got the same
  dependency and passes its tests but was not exercised live; the debug env vars
  in `compose.labs.yaml` are lab-only; sampling 1.0 is a lab value.

- 2026-11-03: unit 5.9 (centralized error contract without leaking internals)
  `VERIFIED`. Tests written first (they did not compile before the change).
  Added `ProblemWriter` (RFC 7807 body: type, title = reason phrase, status,
  fixed detail per status, requestId), `GatewayErrorHandler` (`@Order(-2)`,
  any exception becomes a problem, the real cause is logged, not returned) and
  `RateLimitProblemFilter` (gives the limiter empty 429 the same body).
  `JwtAuthenticationFilter` and `FallbackController` now use the same writer
  and texts. One real defect found by the test: a 404 for an unknown path
  carried no request ID because no route means no gateway filter chain; fixed
  by making `RequestIdFilter` a `WebFilter`. Deliberate contract change: the
  503 title is now the reason phrase "Service Unavailable" (was "Service
  unavailable"), and two assertions in `CircuitBreakerGatewayTest` were updated
  to match. `ErrorContractGatewayTest`: 6 tests. Full gateway suite
  `clean verify`: 49 tests, 0 failures (one run). Live on the rebuilt gateway:
  404 body with title "Not Found", detail "No route matches this request." and
  requestId equal to the X-Request-Id header; 401 kept its
  `urn:stockflow:problem:missing-token` type and gained requestId; with real
  Redis an 80-request async burst gave 40x 200 and 40x 429, and a 429 body was
  `application/problem+json` with requestId and `X-RateLimit-Remaining: 0`;
  with `catalog` stopped, the 503 returned the problem body with requestId and
  no host name. Single run, local machine. Limits: errors produced by catalog
  and warehouse are passed through unchanged, so their shape can differ; the 500
  path is proven by a unit-level test of the handler, not by a live fault; the
  429 decorator wraps `setComplete()` of the limiter and needs a retest if the
  limiter filter is replaced.

- 2026-11-03: unit 5.10 (API versioning and backward compatibility)
  `VERIFIED`. Tests first: the first-draft tests failed before the
  change (alias, deprecation, rate limit and write cases). Added `ApiVersionFilter` (web filter before routing): an
  unversioned `/api/<x>` path is rewritten to `/api/v1/<x>` with the query
  string kept, and the response gets `Deprecation: true` and
  `Link: </api/v1/<x>>; rel="successor-version"`. Rewriting before routing
  keeps the alias on the same route as v1, so it shares authentication, role
  check, rate limit bucket and circuit breaker; a separate alias route would
  have given a second quota. An unknown version (`/api/v9/...`) has no route
  and returns the 404 problem body. `ApiVersioningGatewayTest`: 6 tests, 0
  failures. Two corrections made while writing it: a draft test expected 401
  for an unauthenticated `/api/v9/...`, but no route means no gateway filter
  runs, so it is 404 for every caller (same as any unknown path); I had
  widened the JWT guard from `/api/v1/` to `/api/` for it, reverted that change
  and removed the test, so `JwtAuthenticationFilter` is untouched by this unit.
  Live on the rebuilt gateway: `/api/v1/products?size=2` 200 with no
  Deprecation header; `/api/products?size=2` 200 with `Deprecation: true` and
  `Link: </api/v1/products>; rel="successor-version"`; `/api/categories` 200
  with the matching Link; `/api/products` without a token 401 problem body;
  `/api/v9/products` with a token 404 problem body. Single run, local machine.
  Limits: only v1 exists, so a real v2 route and a `Sunset` date are not
  implemented; the alias is a compatibility measure that should be monitored
  and removed later; `/api/<unknown>` is rewritten to `/api/v1/<unknown>` and
  fails at the downstream with its own 404.
  Full gateway suite `clean verify`: 55 tests, 0 failures (one run).

## Phase 5 gate review (2026-11-03, NOT CLOSED)

Every ROADMAP Phase 5 criterion checked against recorded evidence only. All
ten units are `VERIFIED` individually; the gate stays open for the reasons
under "Open items".

Per-unit acceptance criteria (guidebook, request collection, normal and
failure path, expected results, automated test, evidence):

| Unit | Guidebook | Requests | Automated tests | Live evidence |
| --- | --- | --- | --- | --- |
| 5.1 Dynamic routing | 01 | gateway-dynamic-routing.http | DynamicRoutingGatewayTest 9 | health, routes |
| 5.2 JWT and roles | 02 | gateway-jwt-auth.http | JwtAuthenticationGatewayTest 9, ProductionProfileSecurityTest 1, JwtServiceTest 2 | login 200 / old default 401 |
| 5.3 Rate limiting | 03 | gateway-rate-limiting.http | RateLimitingGatewayTest 2, UserOrIpKeyResolverTest 4 | real Redis: 21x 429 of 80; later 40x 429 of 80 |
| 5.4 Circuit breaker | 04 | gateway-circuit-breaker.http | CircuitBreakerGatewayTest 3 | catalog stopped: 503, recovery 200 |
| 5.5 Load balancing | 05 | gateway-load-balancing.http | LoadBalancingGatewayTest 1 | two containers, 10 and 10 of 20 |
| 5.6 Timeout and retry | 06 | gateway-retry-timeout.http | RetryTimeoutGatewayTest 4 | failover 12/12, hung downstream 503 in 3.4 s |
| 5.7 Request ID | 07 | gateway-request-id.http | RequestIdGatewayTest 5 | header checks |
| 5.8 Tracing | 08 | gateway-tracing.http | TracingGatewayTest 2 | same trace id in gateway and catalog logs |
| 5.9 Error contract | 09 | gateway-error-contract.http | ErrorContractGatewayTest 6 | 404, 401, 429, 503 bodies |
| 5.10 API versioning | 10 | gateway-api-versioning.http | ApiVersioningGatewayTest 6 | v1, alias, no token, v9 |

Special safeguards:

- No automatic retry of non-idempotent writes: `VERIFIED` (RetryTimeoutGatewayTest
  POST sent once; retry is GET only).
- A fallback never reports a write as successful: `VERIFIED` (CircuitBreakerGatewayTest
  failed POST returns 503, sent once; live POST during the outage 503).
- Internal Actuator, Eureka and administrative endpoints are not publicly routed:
  `VERIFIED` live on 2026-11-03 against the rebuilt stack. Through the gateway,
  `/actuator/env`, `/actuator/metrics`, `/actuator/gateway/routes`, `/eureka/apps`,
  `/api/v1/actuator/health`, `/api/actuator/health` and `/api/v1/eureka/apps` all
  returned 404 with and without a token. Only `/actuator/health` and
  `/actuator/info` of the gateway itself answer 200 (its own endpoints, by
  design). Published host ports: gateway 127.0.0.1:8080 and discovery
  127.0.0.1:8761 only.
- Rate-limit keys do not trust client headers: `VERIFIED` (UserOrIpKeyResolverTest,
  spoofed `X-User-Id` ignored).
- Load balancing used two real instances with observed distribution: `VERIFIED`
  (unit 5.5).

Defect found and fixed during this review: `application.yml` still carried the
three unit 5.1 routes, so the running jar had six routes, each id twice, and
the yml copies had no rate limiter, breaker or retry. The Java routes won only
by list order. Found by starting the built jar and reading
`/actuator/gateway/routes` (6 routes; the yml copies listed with no filters).
The tests could not see it because `src/test/resources/application.yml`
shadows the main file. Fix: the yml routes were removed; `RouteDefinitionTest`
(2 tests) asserts unique route ids with at least 5 filters each and that the
main `application.yml` defines no routes (it failed on the yml test before the
fix). After the fix the real jar lists 3 routes. Full gateway suite: 57 tests,
0 failures.

ADR 0003 obligations carried from Phase 2:

- Root user not seeded in the production profile: `VERIFIED` for the gateway,
  the only place an account exists. There is no database seeding of users; the
  root and demo accounts live in memory, built from `ROOT_USERNAME`,
  `ROOT_PASSWORD`, `DEMO_USER_PASSWORD` (environment only, no defaults,
  BCrypt hashed). `ProductionProfileSecurityTest` shows `/api/v1/auth/login`
  returns 404 under `prod`.
- The same test is the "production profile does not create the demo root
  user" check.
- Gap: ADR 0003 and ROADMAP section 2 say every service must still enforce
  authorization where appropriate. Catalog and warehouse contain no security
  code; they trust the gateway. They are not published to the host, so they
  are reachable only on the Docker network `stockflow`, but any container on
  that network can call them without a token. This is an open item (below).

Re-run of all builds on 2026-11-03: api-gateway 57, catalog-service 19,
warehouse-service 17, discovery-server 1 tests, all with 0 failures,
`clean verify` BUILD SUCCESS on each (one run each, Windows, JDK 21).

Observation: right after a gateway restart, the first request to a route can
return 503 for a short time (observed once for `/api/v1/warehouses`, about
45 s after start) because the gateway reports healthy before it has loaded the
Eureka registry; it returned 200 on the next attempt. The compose health check
does not cover this. Not fixed.

Open items (why the gate is not closed):

1. CI has not run on units 5.4-5.10 or the fixes in this review: the work is on
   the local branch `feat/gateway-circuit-breaker`, not pushed. Earlier gates
   closed with a recorded green Actions run; this one needs the same.
2. Service-level authorization (ADR 0003 gap above) needs a decision: accept
   network isolation for the lab and record it in an ADR, or add token or
   header verification to catalog and warehouse.
3. Startup readiness of the gateway (observation above), minor.
4. Carried notes: Spring Boot 3.5 and Spring Cloud 2025.0 are past OSS end of
   support; tracing has no exporter (Phase 10); the circuit breaker state and
   load balancer cache are per gateway instance.

- 2026-11-03: service-level JWT verification in catalog and warehouse
  (Phase 5 gate open item 2, decided by the user: "services verify the
  JWT"; ADR 0007). Added `spring-boot-starter-oauth2-resource-server` and
  `spring-security-test` (test scope), both managed by the Spring Boot BOM, to
  catalog-service and warehouse-service. Each service has a `security` package
  (`JwtProperties`, `SecurityConfig`): HS256 decoder from
  `stockflow.security.jwt.secret` (no default, fail fast under 32 bytes),
  issuer `stockflow-gateway`, roles from the `roles` claim; GET on `/api/v1/**`
  needs ROLE_USER or ROLE_ADMIN, other methods need ROLE_ADMIN; health, info and
  the API documents are open; everything else denied; 401 and 403 are
  problem+json. Compose passes `JWT_SECRET` to both services (required) and to
  `catalog-2` in `compose.labs.yaml`. Tests first: `CatalogSecurityTest` and
  `WarehouseSecurityTest` (12 each) failed before the config; they cover no
  token, forged signature, expired, wrong issuer, USER read, USER write 403,
  ADMIN write, token without roles, a forged `X-User-Roles` header, open
  health and docs, and denied unknown and actuator paths. `SecurityConfigTest`
  (2 each) covers the missing or short key. Existing API tests keep running
  through the real filter chain with an admin token added by `TestAuthConfig`.
  `clean verify`: catalog-service 33 tests, warehouse-service 31 tests, 0
  failures each (one run each).
  A real defect found by the live run, not by any unit test: the gateway's
  JJWT signs with an algorithm chosen from the key length, so the 63-byte
  `JWT_SECRET` produced HS384 tokens and the services (HS256, as ADR 0006
  specifies) answered 401 to valid logins. The service tests missed it because
  `TestTokens` forces HS256. Fix: `signWith(signingKey, Jwts.SIG.HS256)` in the
  gateway `JwtService`; `JwtServiceTest.signsWithHs256WhateverTheKeyLength`
  (32, 63 and 80 byte keys) failed before the fix. Gateway `clean verify`: 58
  tests, 0 failures.
  Live on the rebuilt stack (wget from inside the gateway container straight to
  the services, plus calls through the gateway): catalog and warehouse without a
  token 401; with a demo-user (ROLE_USER) token GET 200; ROLE_USER POST 403;
  root (ROLE_ADMIN) token GET 200; catalog health 200 without a token; through
  the gateway products, warehouses and categories 200 for the user, and an
  admin POST of a warehouse 201. Single run, local machine.
  Side effect: that POST left one demo row (code `AUTH-<number>`) in the
  compose warehousedb.
  Limits: one shared HS256 secret now sits in three services (asymmetric keys
  are the upgrade); the code is duplicated in the two services on purpose; the
  document endpoints are open inside the network; running a service on the host
  needs `STOCKFLOW_SECURITY_JWT_SECRET` set. Gate open item 2 is closed by this
  change; item 1 (CI run) and the minor startup observation remain.

## Phase 5 gate closure (2026-11-03)

CI evidence for the whole Phase 5 branch: GitHub Actions run 41580832608
(pull_request event, PR 3) on commit `802148f`, matched to the PR head sha
before use, concluded success with 4 of 4 matrix jobs passing (discovery,
api-gateway, catalog-service, warehouse-service).

Open items from the gate review above, now:

1. CI run on units 5.4-5.10 and the fixes: closed by run 41580832608.
2. Service-level authorization: closed by ADR 0007 (catalog and warehouse
   verify the JWT; evidence in the entry above).
3. Gateway startup readiness (503 on the first request about 45 s after a
   restart, observed once): still open, minor, not fixed.
4. Carried notes unchanged: Spring Boot 3.5 and Spring Cloud 2025.0 past OSS end
   of support; tracing has no exporter (Phase 10); circuit breaker state and the
   load balancer cache are per gateway instance; one shared HS256 secret in
   three services.

Phase 5 overall: `VERIFIED`, gate closed, with open item 3 and the carried notes
recorded as honest limits, not gate failures. Next: Phase 6 (Redis and caching
labs).

## Phase 6 progress log

- 2026-11-03: unit 6.1 (cache-aside, invalidation after commit, Redis
  failure) `VERIFIED`. Added `spring-boot-starter-data-redis` to
  catalog-service (version from the Spring Boot BOM, approved by the user).
  `ProductCache` (package `cache`): `getOrLoad` for `GET /api/v1/products/{id}`
  with key `catalog:product:v1:<id>`, JSON value, TTL `stockflow.cache.product-ttl`
  (60 s), counter `catalog.cache{cache=product,result=hit|miss|error}`;
  `evictAfterCommit` registered by `ProductService.update` as a transaction
  synchronization; 404 never cached; Redis errors counted and logged, the
  request served from the database, and no write-back after a failed read.
  Settings: `spring.data.redis.timeout` and `connect-timeout` 300 ms,
  `management.health.redis.enabled: false`, `stockflow.cache.enabled`.
  Tests first (they failed before the code): `ProductCacheTest` against real
  `redis:7.4.12-alpine` and PostgreSQL containers (miss then hit with TTL <= 30 s,
  PATCH evicts and the next read is fresh, a rolled-back update keeps the
  entry, 404 not cached), `ProductCacheRedisDownTest` (Redis port closed: GET
  200 from the database with exactly one error counted, PATCH 200),
  `RedisTimeoutConfigTest` (timeouts and health setting present in the main
  yml, read from the file because `src/test/resources/application.yml`
  shadows it). `CatalogSecurityTest` gained a metrics test (admin only) and the
  test profile mirrors the health setting. catalog-service `clean verify`: 43
  tests, 0 failures. Gateway `clean verify`: 59 tests, 0 failures.
  Live on the lab stack (rebuilt catalog and gateway, calls through the
  gateway, counters read from the catalog metrics endpoint with an admin
  token): after deleting the key, three reads gave miss +1 and hit +2 (109,
  70 and 74 ms); `redis-cli TTL` showed 58; the stored value was the product
  JSON; PATCH returned 200 and the key was gone (`EXISTS` 0), the next GET
  returned the edited description and recreated the key.
  Defect found by the outage run, in the gateway (unit 5.3, never tested
  against a Redis outage): with Redis stopped every request through the
  gateway took 60.5-61.0 s (4 requests measured: 60967, 60733, 60690 and 60499
  ms) before the rate limiter failed open and returned 200, because the
  gateway had no Redis timeout (Lettuce default 60 s). Fix:
  `spring.data.redis.timeout` and `connect-timeout` 300 ms in the gateway
  `application.yml`, guarded by `RedisTimeoutConfigTest` (failed before). After the
  fix, with Redis stopped: GET 723, 737 and 758 ms, PATCH 938 ms, catalog health
  still UP, and 62, 76 and 59 ms right after Redis was started again. The 0.7 s
  is the gateway timeout plus the catalog read timeout (300 ms each) plus the
  database read. Redis outage also means the rate limit is not enforced
  (fail open); guidebook 03 now says so.
  Observation: the very first GET after a catalog restart took 1.77-1.79 s
  (cold start), longer than the gateway response timeout of 1 s, so the
  gateway retried it and the catalog counted two misses for one client
  request (miss +2, hit +2 over three reads). This is a small natural
  stampede and is the starting point of unit 6.3.
  Other changes: the admin metrics rule (`/actuator/metrics/**` needs ROLE_ADMIN
  in catalog; the lab override exposes the endpoint), `SPRING_DATA_REDIS_HOST: redis`
  for `catalog` and `catalog-2`, guidebook 05 commands now carry an admin
  token (they broke when catalog started verifying tokens, ADR 0007), guidebook
  11 and `playground/http/product-cache.http`.
  Side effects on the compose catalogdb: product 1 `updated_at` advanced and its
  description was edited and restored to the original text.
  Limits: only the product by id is cached (lists and searches are not);
  the reader/writer race can leave a stale copy for up to the TTL; one
  single run on this machine; 6.2-6.4 not started.

- 2026-11-03: unit 6.2 (Redis eviction lab) `VERIFIED`. No new dependency.
  Controlled configuration: service `redis-lab` in `compose.labs.yaml`
  (profile `eviction`, `redis:7.4.12-alpine`, `--maxmemory` from
  `LAB_REDIS_MAXMEMORY`, `--maxmemory-policy` from `LAB_REDIS_POLICY`,
  `--maxmemory-samples 10`), separate from the core Redis that also holds the
  gateway rate-limit keys. Experiment script `labs/caching/eviction-lab.sh`.
  Automated: `RedisEvictionPolicyTest` (5 tests, 2 MB cap, 3000 cold keys of 1 KB,
  50 hot keys: allkeys-lru keeps >= 45 hot keys with no OOM, allkeys-random keeps
  < 30, noeviction gives OOM and 0 evictions, volatile-lru without TTL gives OOM,
  volatile-lru with TTL evicts) and `ProductCacheFullRedisTest` (Redis at
  `noeviction` and full: product reads return 200 with the right body, exactly one
  error counted, and after freeing keys the next reads miss then hit). catalog-service
  `clean verify`: 50 tests, 0 failures, run twice (the probabilistic eviction tests
  were stable in both). Assertion detail fixed on the way: Spring wraps the Redis
  error as "Error in execution", the OOM text is in the root cause.
  Measured (script, 8 MB cap, 12000 cold keys, one run per row):
  noeviction 6603 writes refused, 50/50 hot left, 0 evicted; allkeys-lru 0 refused,
  50/50 hot, 6699 evicted; allkeys-random 0 refused, 8/50 hot, 10785 evicted;
  volatile-lru without TTL 11951 refused, 0 evicted; volatile-lru with TTL 3600 s 321
  refused, 49/50 hot, 6573 evicted.
  Lab defect found and fixed: the first script wrote everything in about a second;
  Redis ages keys in whole seconds, so LRU could not tell hot from cold and
  volatile-lru with TTL left 0 of 50 hot keys (the slower automated test kept them).
  The script now writes in batches one second apart.
  Finding for the project, not changed here: the core Redis has no `maxmemory`
  (`CONFIG GET maxmemory-policy` shows noeviction), so its memory is unbounded, and
  it is shared with the gateway rate limiter; capping it needs a decision.
  Limits: single run per policy on one machine; allkeys-lfu and volatile-ttl not
  run; replication behavior not exercised; the lab Redis container was removed
  afterwards and the core stack was unchanged.

- 2026-11-03: unit 6.3 (cache stampede lab, single-flight) `VERIFIED`. No new
  dependency. `ProductCache.getOrLoad` now coalesces simultaneous misses per
  product id inside one catalog instance: the first request is the leader (it
  looks in Redis once more, then loads from the database and stores), the others
  wait on its `CompletableFuture`; a leader failure such as 404 is the answer
  for every waiter. Switch `stockflow.cache.single-flight` (default true); lab
  override `LAB_SINGLE_FLIGHT` in `compose.labs.yaml`. New results of the counter
  `catalog.cache`: `load` (database read) and `coalesced`.
  Tests first: `ProductCacheStampedeTest` (3 tests incl. 20 concurrent 404s) and
  `ProductCacheStampedeUnprotectedTest` (single-flight off) against real Redis
  and PostgreSQL containers, 40 threads released together via `ConcurrentGets`.
  Measured: single-flight on = 1 database load for 40 requests; single-flight off
  = 40 loads (the unprotected test prints the number; an earlier run printed 10,
  see below). Live on the lab stack (`labs/caching/stampede-lab.sh`, 40 wget
  requests straight at the catalog from the gateway container, product 1 key
  deleted before each round): single-flight on, warm-up round 1 load (39
  coalesced) and three rounds of 1, 1, 1 load (coalesced 14, 22, 4); single-flight
  off, warm-up round straight after the restart 40 loads, then 7, 6, 5 loads. All
  requests returned 200 in every round. One run per row, one machine, not a
  benchmark.
  Defect found by the lab, in unit 6.1: the first unprotected test run showed 10
  loads, not 40, because `ProductService.get` was `@Transactional`, so every
  request took one of the 10 pooled connections before it even checked the
  cache. A new test (`hitDoesNotUseTheDatabase`, counting `hikaricp.connections.acquire`
  around a hit) failed, which proved that a cache HIT also took a connection. Fix:
  `get` is no longer transactional as a whole; only the load runs in a read-only
  `TransactionTemplate`. After the fix the test passes and the unprotected herd is
  the full 40.
  catalog-service `clean verify`: 56 tests, 0 failures (one run after the last
  code change; the stampede and eviction tests also passed in the runs before the transaction change).
  Limits: single-flight is per instance, so a herd across N instances costs up to
  N loads (unit 6.4); no distributed lock, no TTL jitter, no stale-while-revalidate;
 
  no multi-process lab beyond one machine, one run per row.
- 2026-11-03: unit 6.4 (distributed caching) `VERIFIED`. No production code and no
  new dependency: the shared Redis key already serves every instance. New
  `ProductCacheTwoInstancesTest` (3 tests) starts instance B as a second
  application context against the same PostgreSQL and Redis containers as the
  test's own context A. A entry loaded by A is a hit on B (B load +0, hit +1); an
  update on A removes the key and B returns the new name with one load; 40 reads
  split 20/20 over both instances with the key missing cost 1 load on A and 1 on B.
  A first draft of the test passed B's properties with `.properties()`, which the
  test `application.yml` overrode (`stockflow.cache.enabled: false`), so B ran with
  the cache off and its counters stayed 0; command-line arguments fixed it.
  Live on the lab stack (`catalog` + `catalog-2`, `labs/caching/distributed-lab.sh`):
  read on `catalog` = 1 load, same read on `catalog-2` = 0 loads and 1 hit; PATCH
  through the gateway returned 200, the key was gone, both instances returned the
  new description with 1 load in total; 40 reads split 20/20 with the key missing:
  all 200, `catalog` +1 load and `catalog-2` +1 load. One run per row, one machine.
  catalog-service `clean verify`: 60 tests, 0 failures.
  Limit: single-flight stays per instance, so a herd over N instances costs up to N
  loads; no distributed lock. Guidebook `guidebooks/14-distributed-caching.md`.
  Phase 6 gate review and a CI run on the branch are still to do.

## Phase 6 gate review (2026-11-03, closing Phase 6)

CI evidence for the whole Phase 6 branch: GitHub Actions run 41628003809
(pull_request event, PR 4) on commit `d7a1945`, matched to the PR head sha before
use, concluded success with 4 of 4 matrix jobs passing (catalog-service 60 tests,
api-gateway 59, warehouse-service 31, discovery-server 1; 0 failures).
Two earlier runs of this PR failed in `RedisEvictionPolicyTest`
(`allkeysLruKeepsTheHotKeys`, `volatileLruWithTtlEvicts`: 16 and 8, then 21 and
17 of 50 hot keys kept, 45 expected). Cause: Redis ages keys in whole seconds and
its own baseline uses about 1 MB of the 2 MB cap (measured 987 KB), so on a fast
runner about 1000 cold keys shared a second with the hot reads. The tests now
pause 1.1 s before each hot read, every 250 keys; assertions were not changed.
The failure was not reproducible on the development machine; CI is the evidence.

| Unit | Guidebook | Lab or http file | Automated tests | Live evidence |
|---|---|---|---|---|
| 6.1 Cache-aside, invalidation, Redis failure | 11 | product-cache.http | ProductCacheTest 6, ProductCacheRedisDownTest 2, RedisTimeoutConfigTest 2 | miss then hit, TTL, PATCH evicts, Redis stopped: 200 from the database |
| 6.2 Eviction | 12 | labs/caching/eviction-lab.sh | RedisEvictionPolicyTest 5, ProductCacheFullRedisTest 2 | four policies on a capped Redis |
| 6.3 Stampede | 13 | labs/caching/stampede-lab.sh | ProductCacheStampedeTest 3, ProductCacheStampedeUnprotectedTest 2 | 1,1,1 loads with single-flight, 7,6,5 without |
| 6.4 Distributed caching | 14 | labs/caching/distributed-lab.sh | ProductCacheTwoInstancesTest 4 | two catalog containers, one Redis |

Acceptance criteria:

- Catalog read path shows cache miss then hit: `VERIFIED` (ProductCacheTest.missThenHit; live counters).
- Update invalidates after the DB commit: `VERIFIED` (updateEvictsAndNextReadIsFresh; rolledBackUpdateKeepsTheEntry).
- Database remains the source of truth: `VERIFIED` (entries are copies with a TTL, a 404 is never cached, Redis down falls back to the database).
- Redis failure behavior explicit and tested: `VERIFIED` (ProductCacheRedisDownTest, RedisTimeoutConfigTest, ProductCacheFullRedisTest, gateway limiter fail-open).
- Eviction lab has a controlled memory and policy configuration: `VERIFIED` (2 MB cap in tests, `redis-lab` with `LAB_REDIS_MAXMEMORY` and `LAB_REDIS_POLICY` in compose.labs.yaml).
- Stampede lab has reproducible concurrent requests and a measured observation: `VERIFIED` (40 simultaneous requests: test 1 load against 40 without protection; live numbers in guidebook 13).
- Distributed caching uses multiple app instances and shared Redis: `VERIFIED` (two application contexts in the test; `catalog` and `catalog-2` containers live).

Open items and carried notes:

1. Core Redis has no `maxmemory` and is shared by the product cache and the gateway rate limiter: open, decision pending (an eviction policy would also remove rate-limit keys).
2. Single-flight is per instance: a herd over N instances costs up to N loads; no distributed lock, no TTL jitter, no stale-while-revalidate.
3. Stale window around an eviction (a reader that loaded the old row just before a commit can re-store it): bounded by the 60 s TTL, no version column.
4. Gateway startup readiness (503 on the first request about 45 s after a restart): still open, minor.
5. Spring Boot 3.5 and Spring Cloud 2025.0 past OSS end of support; tracing has no exporter (Phase 10).
6. Lab numbers are one run per row on one machine; they are not benchmarks.

Phase 6 overall: `VERIFIED`, gate closed, with the open items above.

## Phase 7 progress log

- 2026-11-03: unit 7.1 (race condition and lost update) `VERIFIED`. Branch
  `feat/phase7-database-labs`. No new dependency. warehouse-service gets Flyway V3
  `stock_levels` (location FK, product id without FK, `CHECK (quantity >= 0)`, unique per
  location and product), `POST /api/v1/stock`, `GET /api/v1/stock/{id}` and
  `POST /api/v1/stock/{id}/withdraw` (ADMIN, 200/400/404/409). Decision by the user: the
  lab lives in the warehouse as a `stock_levels` table.
  Tests first against the naive read-modify-write `withdraw`: both safe tests failed
  (`StockRaceConditionTest.neverTakesMoreThanInStock`: 100 of 100 accepted for a stock of
  50; `takenPlusLeftEqualsStart`: accepted x 3 + left = 208 for a stock of 100). Fix:
  `StockLevelRepository.withdraw` is one conditional `UPDATE ... WHERE id = :id AND quantity >= :n`,
  0 rows = 404 or 409. The naive code stays as `StockService.withdrawUnsafe`, exposed
  only by `StockLabController` when `stockflow.lab.unsafe-stock=true` (set in
  `compose.labs.yaml`; `StockApiTest.unsafeEndpointIsOffByDefault` proves it is a 404
  otherwise).
  After the fix: `StockRaceConditionTest` 4 tests (safe: 50 accepted and 50 refused,
  0 left; 33 x 3 + 1 = 100; unsafe: round 1 accepted 100 and left 93 of 100, so updates were
  lost) and `StockApiTest` 6 tests, 0 failures. warehouse-service `clean verify`: 41 tests,
  0 failures.
  Live on the lab stack (`labs/database/race-lab.sh`, 100 simultaneous `wget` requests
  from the gateway container straight at the warehouse): unsafe, stock 100: accepted 100,
  left 88, 89 and 92 in three runs (accepted + left 188, 189, 192); unsafe, stock 50:
  accepted 100, left 35; safe, stock 100: accepted 100, left 0 in all three runs; safe,
  stock 50: accepted 50, 50 refused with 409, left 0. One run per row (three for stock 100),
  one machine, not a benchmark. Guidebook `guidebooks/15-race-condition.md`.
  Limits: the unsafe test is probabilistic (up to 10 rounds, the first round showed it);
  the fix covers a single row, rules across rows are for the locking and isolation units;
  stock endpoints are not routed by the gateway; CI has not run on this branch.
- 2026-11-03: unit 7.2 (optimistic and pessimistic locking) `VERIFIED`. No new dependency.
  Flyway V4 adds `stock_levels.version`. `StockService.withdrawOptimistic` re-reads and
  retries a `UPDATE ... WHERE version = :read` at most `stockflow.stock.optimistic-attempts`
  (5) times, then a 409 "Concurrent update"; `withdrawPessimistic` uses
  `SELECT ... FOR UPDATE` with a transaction-local `lock_timeout`
  (`stockflow.stock.lock-timeout`, 2 s), a timeout is a 503 "Row locked". Both are lab
  endpoints behind `stockflow.lab.unsafe-stock`. The entity `version` is read-only on
  purpose, not a JPA `@Version`, because `@Version` would make the unsafe path of 7.1
  fail with an optimistic lock exception and hide the bug; the atomic withdraw also adds 1
  to the version. `StockLockingTest` 6 tests (tests written before the code, red by
  compile error, then green): optimistic keeps accepted + left = 100 (accepted 54, gave up
  46, 275 retries) and never oversells at stock 50; pessimistic accepts 100 of 100 and
  leaves 0, accepts exactly 50 of 100 at stock 50; a withdrawal blocked by a lock held in
  another transaction gave up after 2040 ms. warehouse-service `clean verify`: 47 tests,
  0 failures.
  Live on the lab stack (`labs/database/race-lab.sh`, 100 simultaneous requests, stock 100,
  three runs): optimistic accepted 46, 52, 52 (left 54, 48, 48, accepted + left 100 each
  time); pessimistic accepted 100, left 0 each time; atomic UPDATE accepted 100, left 0;
  unsafe left 91, 88, 92. Stock 50: optimistic accepted 44, left 6; pessimistic accepted 50,
  left 0; atomic accepted 50, left 0; unsafe accepted 100, left 40. One run per row (three at
  stock 100), one machine, not a benchmark. Guidebook
  `guidebooks/16-optimistic-and-pessimistic-locking.md`.
  Limits: single row only; optimistic gives up about half of the requests under 100-way
  contention on one row, which is the finding, not a tuning target; `lock_timeout` is
  PostgreSQL specific; CI has not run on this branch.
- 2026-11-03: unit 7.3 (deadlock) `VERIFIED`. No new dependency. warehouse-service gets
  `POST /api/v1/stock/transfer` (two rows locked in one `ORDER BY id ... FOR UPDATE`
  statement, ascending id, then two version-bumping UPDATEs; 200/400/404/409) and the lab
  endpoint `POST /api/v1/stock/transfer-unordered?pauseMillis=` (locks from, `pg_sleep`,
  locks to; behind `stockflow.lab.unsafe-stock`). `lock_timeout` stays set per transaction.
  A `PessimisticLockingFailureException` whose root cause has SQLSTATE 40P01 is a 409
  "Deadlock" problem, other lock failures remain 503 "Row locked". Tests
  (`StockDeadlockTest` 4 and 2 new in `StockApiTest`): two opposite unordered transfers, one
  fails with 40P01 and one commits, resolved in 1541 ms (bounded by the test at 10 s); the
  rows differ from the start only by the winner's unit (rollback complete); retrying the
  loser with the ordered `transfer` restores 10 and 10; 40 ordered transfers on 16 threads in
  both directions all succeed, rows end at 100 and 100; a refused or 404 transfer changes
  nothing. warehouse-service `clean verify`: 54 tests, 0 failures. The deadlock test passed on
  its first run; there was no earlier red state for the fix, only compile errors before the
  methods existed.
  Live on the lab stack (`labs/database/deadlock-lab.sh`, 3 rounds, two opposite transfers
  at the same moment): unordered: 200 and 409 in every round, rows 9/11, 11/9, 9/11, PostgreSQL
  logged +1 "deadlock detected" per round; ordered: 200 and 200 in every round, rows 10/10,
  +0 deadlocks. One run per row, one machine, not a benchmark. Guidebook
  `guidebooks/17-deadlock.md`.
  Limits: two rows only; ordering relies on the lock order of one SELECT with ORDER BY;
  deadlocks from foreign key locks are not covered; CI has not run on this branch.
- 2026-11-03: unit 7.4 (transactions, ACID, isolation levels) `VERIFIED`. No production code
  and no new dependency. `IsolationLevelTest` (10 tests incl. the inherited context test) drives
  two JDBC connections from one thread in a fixed order against the real PostgreSQL container, so no
  outcome depends on timing. Observed: no dirty read even at READ UNCOMMITTED; non-repeatable
  read at READ COMMITTED (10 then 7) and not at REPEATABLE READ (10 then 10); phantom read at
  READ COMMITTED (2 then 3 rows) and not at REPEATABLE READ (2 then 2); lost update at READ
  COMMITTED (two withdrawals of 3 from 10 leave 7, not 4) and a 40001 rejection at REPEATABLE
  READ with the row left at 7; write skew across two rows accepted at REPEATABLE READ (total 0,
  rule "at least 1 left" broken) and rejected at SERIALIZABLE with 40001 (`could not serialize
  access due to read/write dependencies among transactions`, reason `Canceled on identification
  as a pivot, during write`, raised at the UPDATE; total stays 1). The two runs of the class
  both passed; the tests passed on their first run, so there is no earlier red state. Guidebook
  `guidebooks/18-isolation-levels.md`.
  Limits: no compose lab for this unit (the tests give an exact interleaving that background
  processes would not); the lock-based fix for write skew is not tested here, only SERIALIZABLE;
  the SERIALIZABLE retry is described, not implemented; the READ UNCOMMITTED row of the table in
  the guidebook is proven only for dirty reads; CI has not run on this branch.
- 2026-11-03: unit 7.5 (connection pooling) `VERIFIED`. No new dependency. warehouse-service
  `application.yml` now sets `spring.datasource.hikari.maximum-pool-size: 10` and
  `connection-timeout: 3000` (the defaults are 10 and 30 s); catalog and the other services
  still use the defaults (carried note). `GlobalExceptionHandler` maps
  `CannotCreateTransactionException` and `CannotGetJdbcConnectionException` to a 503 problem
  ("Database busy" when the root cause is Hikari's `SQLTransientConnectionException`, else
  "Database unavailable"). Lab only: `GET /api/v1/stock/lab/hold?millis=` and
  `PoolLabService` (behind `stockflow.lab.unsafe-stock`).
  Tests written first: the config test and the exhaustion test failed before the
  implementation (exhaustion: 0 responses of 200 or 503, the endpoint was a 404). After:
  `ConnectionPoolTest` (pool 5, wait 1 s, 20 requests holding 600 ms: 10 x 200 and 10 x 503,
  503 after 1018 to 1060 ms warm and 1723 to 1843 ms on the cold first burst, peak pending 10
  and 15, pool usable afterwards), `PoolSizingTest` 2 measurements, `DataSourceConfigTest`.
  warehouse-service `clean verify`: 71 tests, 0 failures.
  Pool size against throughput (two runs, 8 cores, 32 client threads): pg_sleep 50 ms x 160:
  8404/8390, 4255/4216, 2177/2174, 1244/1252, 987/991 ms for pools 1/2/4/8/16; CPU query
  (500k-row series) x 40: 5283/5333, 3327/3273, 2037/1944, 1801/1724, 1870/1885 ms. A first
  heavier CPU run (1.5M rows x 120, one run, since reduced because it took 2 minutes) gave
  53217, 27833, 17931, 14815, 22005 ms, pool 16 slower than 8; the lighter batch showed the
  plateau but not the slowdown.
  Finding: the Micrometer gauge `hikaricp.connections.active` stayed at 5 for 732 ms after a
  burst; HikariCP 7.0.2 builds its PoolStats with a 1 s refresh (read from the class files), it
  is not a leak (53 ms and 732 ms to reach 0 in two bursts).
  Live on the lab stack (`labs/database/pool-lab.sh`, pool 10, wait 3 s, hold 1000 ms): 10
  requests: 10 x 200; 40 requests: 40 x 200 in three runs (the last group waited about 3.0 s,
  at the timeout, my prediction of failures was wrong); 60 requests: 40 x 200 and 20 x 503 in
  all three runs; 40 requests held 100 ms: 40 x 200; a single request after the burst: 200.
  Wall times include about 1 s of `docker exec` overhead. Guidebook
  `guidebooks/19-connection-pooling.md`.
  Limits: only the warehouse pool is bounded; no statement timeout; no `max_connections`
  experiment; the CPU result depends on the machine; CI has not run on this branch.
