# ADR 0004 - JDK toolchain for building and running

Status: Accepted (Phase 1 prerequisite). Measured facts, 2026-10-28:
`java -version` reports OpenJDK 25.0.4.1 LTS (Temurin) as the shell default,
while `mvn -version` reports Apache Maven 3.9.16 resolving JAVA_HOME =
Temurin JDK 21.0.12.1. A JDK 21 is therefore installed and is what builds
use; an earlier draft claiming no JDK 21 was confirmed locally was corrected
by this measurement.

## Context

The ROADMAP pins the project's Java target to 21 LTS. Two JDKs exist on this
machine: the shell default is 25, and Maven resolves a real JDK 21 (Temurin
21.0.12.1) through JAVA_HOME. The clean path: build and test exactly on the
JDK 21 Maven already resolves, enforce the 21 language level in poms, and
pin Temurin 21 for CI and container runtimes.

## Decision

- The Java target stays **21** (no change to the ROADMAP baseline).
- Poms compile with Maven's `--release 21` (the Spring Boot parent maps
  `java.version=21` to the compiler release flag), so language level and
  bytecode are exactly Java 21 regardless of the local default JDK.
- Local builds and tests run on the JDK 21 that Maven resolves (Temurin
  21.0.12.1 via JAVA_HOME). Test evidence names that JDK, so runtime claims
  are Java-21-runtime claims.
- Where real runtime distribution matters, JDK 21 is pinned explicitly:
  CI setups use Temurin 21 via `setup-java` (declared when CI lands in
  Phase 1), and Dockerfiles use a Temurin 21 base image (Phase 4).
- Language discipline follows JAVA_RULES: only Java 21 features; `--release
  21` enforces it at compile time.

## Consequences

- No additional JDK installation is required to start Phase 1; the pinned
  local toolchain is Maven 3.9.16 on Temurin JDK 21.0.12.1 (versions recorded
  here, and echoed again when wrapper generation pins the distribution).
- Every verification record names the JDK it ran on (currently Temurin
  21.0.12.1 via Maven). The shell's `java -version` default (25.0.4.1) is a
  shell fact, not the build reality.
- CI and containerized runs will provide the 21-runtime evidence the RUNTIME
  claims need; local runs provide compile/test evidence for `--release 21`.
