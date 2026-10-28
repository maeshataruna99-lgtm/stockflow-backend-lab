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
units 3.3-3.4 pending. Phase 0 evidence below remains the
gate.

## Last verified commit

`78569f3` (feat(catalog): jpa foundation with testcontainers integration
tests) is the latest code state, verified green by Actions run
40665107969 (all four matrix jobs SUCCESS; catalog job ran 4 tests,
1+3, with the Testcontainers container and Flyway migration executing
on the runner). Phase 2 code evidence chain: unit 2.1 commit `e5ecdee`
(run 40648268572), unit 2.2 commit `0c350e4` (run 40651087397); live
psql and restart evidence 14:05-14:35+07:00 below.

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
  so "OpenAPI reflects actual behavior" is not yet claimed.
