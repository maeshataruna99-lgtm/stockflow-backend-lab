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
below. Phase 5 - API gateway and its ten use cases - is next. Phase 0
evidence below remains the gate.

## Last verified commit

`e59df72` (feat(compose): core stack with internal network and
discovery) is the latest code state, verified green by Actions run
40674415027 (all four matrix jobs SUCCESS). Earlier chain: unit 4.1
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
