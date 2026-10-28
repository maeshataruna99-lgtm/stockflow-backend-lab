# ADR 0004 - JDK toolchain for building and running

Status: Accepted (Phase 1 prerequisite). Measured fact: the local system
default JDK is OpenJDK 25.0.4.1 LTS (Temurin), recorded via `java -version`
in `docs/STATUS.md` on 2026-10-28.

## Context

The ROADMAP pins the project's Java target to 21 LTS, but no JDK 21 is
confirmed installed locally; the default JDK is 25. Phase 1 can start in two
honest ways: install and pin a local JDK 21, or build on the installed JDK
while enforcing the Java 21 language level and bytecode target - with CI and
container runtimes pinned to a real JDK 21 image.

## Decision

- The Java target stays **21** (no change to the ROADMAP baseline).
- Poms compile with Maven's `--release 21` (the Spring Boot parent maps
  `java.version=21` to the compiler release flag), so language level and
  bytecode are exactly Java 21 regardless of the local default JDK.
- Local builds and tests may run on the installed JDK (25 today). This is a
  recorded, accepted condition, not a hidden substitution: Spring Boot 3.5
  officially supports Java 17-25, and test evidence states the JDK it ran on.
- Where real runtime distribution matters, JDK 21 is pinned explicitly:
  CI setups use Temurin 21 via `setup-java` (declared when CI lands in
  Phase 1), and Dockerfiles use a Temurin 21 base image (Phase 4).
- Language discipline follows JAVA_RULES: only Java 21 features; `--release
  21` enforces it at compile time.

## Consequences

- No local JDK installation is required to start Phase 1; this can change if
  evidence starts requiring a 21 runtime (then this ADR is superseded).
- Every verification record must name the JDK it ran on (today: 25.0.4.1) so
  no one mistakes a 25-runtime run for a 21-runtime claim.
- CI and containerized runs will provide the 21-runtime evidence the RUNTIME
  claims need; local runs provide compile/test evidence for `--release 21`.
