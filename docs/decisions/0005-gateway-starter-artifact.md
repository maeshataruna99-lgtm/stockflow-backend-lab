# ADR 0005 - Gateway starter artifact

Status: Accepted (2026-10-28, Phase 1). Supersedes the api-gateway scaffold's
initial dependency choice of `spring-cloud-starter-gateway`.

## Context

The api-gateway scaffold used `spring-cloud-starter-gateway` (the reactive
Spring Cloud Gateway starter) inside the pinned Spring Cloud 2025.0.3 BOM.
Its first real `mvnw verify` run (evidence in `docs/STATUS.md`, 2026-10-28
11:55+07:00) passed, but printed two explicit deprecation warnings emitted
by the starter itself:

- "spring-cloud-gateway-server is deprecated. It will be removed in the next
  major release. Please add spring-cloud-gateway-server-webflux dependency."
- "spring-cloud-starter-gateway is deprecated. It will be removed in the
  next major release. Please use spring-cloud-starter-gateway-server-webflux
  instead."

Maven Central metadata for `spring-cloud-starter-gateway-server-webflux`
(fetched 2026-10-28, `repo.maven.apache.org/.../maven-metadata.xml`) lists
4.3.5 - the same version the deprecated starter resolved to inside the
pinned BOM - so the switch stays within the version baseline of ADR 0001.

## Decision

- The api-gateway depends on `spring-cloud-starter-gateway-server-webflux`
  instead of the deprecated `spring-cloud-starter-gateway`.
- The architectural intent is unchanged: the gateway stays reactive
  (WebFlux) - this starter is the same reactive Spring Cloud Gateway under
  its non-deprecated name. The MVC rule for the services is untouched.
- The version baseline (parent 3.5.16, BOM 2025.0.3, Java 21) is unchanged.

## Consequences

- No deprecated-artifact landmine for the next major Spring Cloud release.
- Re-verify evidence for the switched pom is recorded in `docs/STATUS.md`
  (Phase 1 progress log) after this ADR lands.
