# STATUS - StockFlow Backend Lab

Protocol: only `VERIFIED` counts as green and every entry points at evidence
that actually ran. A checkbox is tracking, not evidence.

## Current phase

Phase 0 - repository and design baseline: `VERIFIED` (committed as
`24addaa`).
Phase 1 - Maven/Spring Boot foundations: `IN PROGRESS` (opened 2026-10-28;
JDK toolchain decided in `docs/decisions/0004-jdk-toolchain.md`; first task is
the `discovery-server` scaffold). Phase 0 evidence below remains the gate.

## Last verified commit

None. This repository has no commits yet and none were requested; all
recorded content is working tree (untracked) on branch `main`.

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
- `java -version` -> OpenJDK `25.0.4.1` LTS (Temurin) as system default.
  Note: the default is Java 25, not 21. Phase 1 needs an explicit decision: a
  pinned JDK 21 install/toolchain, or building with `--release 21` on 25 -
  that choice gets recorded before Phase 1 claims anything.
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

None for Phase 0. For Phase 1 the local default JDK being 25 (not 21) is the
first decision to resolve; nothing else is blocked.

## Phase 1 progress log

- 2026-10-28: JDK prerequisite resolved - the local default JDK stays 25,
  the build target stays Java 21 via `--release 21` (ADR 0004).
- Next: scaffold `discovery-server` (pinned parent 3.5.16 plus Spring Cloud
  2025.0.3 BOM, own Maven Wrapper, minimal Eureka server, context test).
  Until real `mvnw.cmd --batch-mode clean verify` evidence exists, its status
  is `IMPLEMENTED - NOT VERIFIED`.
- Then, one app per task with the same evidence rule: `api-gateway`,
  `catalog-service`, `warehouse-service`.
