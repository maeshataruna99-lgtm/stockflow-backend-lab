# STATUS - StockFlow Backend Lab

Protocol: only `VERIFIED` counts as green and every entry points at evidence
that actually ran. A checkbox is tracking, not evidence.

## Current phase

Phase 0 - repository and design baseline: `VERIFIED` (committed as
`24addaa`).
Phase 1 - Maven/Spring Boot foundations: `VERIFIED` (2026-10-28, gate
closed - evidence below).
Phase 2 - PostgreSQL, Flyway and seed data: `IN PROGRESS` - unit 2.1
(compose postgres + init scripts) `VERIFIED` 2026-10-28; unit 2.2
(Flyway migrations + datasource config) is next. Phase 0 evidence below
remains the gate.

## Last verified commit

`2e43056` (ci: pin actions to current maintained versions) is the latest
code state, verified green by Actions run 40643409693. Local app evidence:
four builds verified 11:50-12:02 and live start-and-health checks passed
13:16-13:21+07:00.

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
