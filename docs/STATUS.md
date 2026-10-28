# STATUS - StockFlow Backend Lab

Protocol: only `VERIFIED` counts as green and every entry points at evidence
that actually ran. A checkbox is tracking, not evidence.

## Current phase

Phase 0 - repository and design baseline: `VERIFIED` (committed as
`24addaa`).
Phase 1 - Maven/Spring Boot foundations: `IN PROGRESS` (JDK toolchain
decided in ADR 0004). All four apps verified: `discovery-server` (11:50),
`api-gateway` (11:55, re-verified 11:58 after the ADR 0005 starter switch),
`catalog-service` (12:00), `warehouse-service` (12:02) - evidence below.
Remaining Phase 1 deliverables: unit-test baseline, CI workflow.
Phase 0 evidence below remains the gate.

## Last verified commit

The warehouse-service scaffold commit `8bbc976` is the latest verified
state; its verify evidence is recorded in the `docs:` commit immediately
following it.

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
