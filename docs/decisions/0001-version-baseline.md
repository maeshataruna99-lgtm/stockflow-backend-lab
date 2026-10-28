# ADR 0001 - Version baseline (pinned)

Status: Accepted (Phase 0 baseline). Facts below were checked against the
listed official sources, all accessed on 2026-10-28.

## Decision

| Component | Pinned version | Basis |
| --- | --- | --- |
| Java | 21 LTS | ROADMAP baseline; Boot 3.5 supports Java 17-25, so 21 is valid. Exact distribution (CI image, Dockerfile base) pinned in Phase 1 build files |
| Spring Boot | 3.5.16 | Maven Central `spring-boot-starter-parent` metadata: latest patch of the 3.5 cycle |
| Spring Cloud | 2025.0.3 (Northfields) | Maven Central `spring-cloud-dependencies` metadata: latest micro of the train; the official Spring Cloud support wiki maps 2025.0 <-> Spring Boot 3.5.x |
| PostgreSQL image | `postgres:16.15-alpine` | Docker Hub tag exists for `library/postgres` (pushed 2026-10-22); endoflife.date lists 16.15 as latest 16.x |
| Redis image | `redis:7.4.12-alpine` | Docker Hub tag exists for `library/redis` (pushed 2026-10-26); satisfies the ROADMAP "Redis 7" baseline |
| OpenAPI docs | `springdoc-openapi-starter-webmvc-ui` 2.10.0 | Maven Central metadata: latest release of the 2.x line; the springdoc README compatibility table maps Spring Boot 3.x -> springdoc 2.x |
| JUnit, Mockito, AssertJ, Testcontainers | managed by the Spring Boot 3.5.16 dependency BOM | no separate pin in Phase 0 |
| Maven Wrapper | distributionUrl pinned per app in Phase 1 | deliberately deferred to the phase that introduces it; pinned exact there, never `latest` |

## Compatibility facts (official)

- Spring Cloud support wiki (edited 2026-03-19): release train 2025.0
  (Northfields) supports Spring Boot 3.5.x; 2025.1 (Oakwood) supports 4.0.x.
- endoflife.date spring-boot data: 3.5 cycle latest patch is 3.5.16
  (released 2026-06-25); OSS support ended 2026-06-30; extended (commercial)
  support runs to 2032-06-30.

## Known caveat (documented trade-off, deliberately not changed)

Spring Boot 3.5.x is compatible with Spring Cloud 2025.0.x, but both are past
their OSS support window (2026-06-30); the actively maintained OSS lines today
are Boot 4.0.x/4.1.x with the Spring Cloud 2025.1.x train. The ROADMAP pins
the 3.5.x/2025.0 combination and forbids silent stack replacement, so this
baseline keeps 3.5.16 + 2025.0.3. Patch upgrades inside 3.5.x cannot exceed
3.5.16. Moving to Boot 4.0.x + 2025.1 is possible only with an explicit user
decision and a superseding ADR.

## Sources (accessed 2026-10-28)

- https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml
- https://repo1.maven.org/maven2/org/springframework/cloud/spring-cloud-dependencies/maven-metadata.xml
- https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions
- https://repo1.maven.org/maven2/org/springdoc/springdoc-openapi-starter-webmvc-ui/maven-metadata.xml
- https://github.com/springdoc/springdoc-openapi (compatibility table)
- https://endoflife.date/api/spring-boot.json
- https://hub.docker.com/v2/repositories/library/postgres/tags?name=16.15-alpine
- https://hub.docker.com/v2/repositories/library/redis/tags?name=7.4.12-alpine

## Consequences

- Every Phase 1+ pom uses `spring-boot-starter-parent:3.5.16`,
  `spring-cloud-dependencies:2025.0.3` (where the train is needed), and Java
  release 21.
- Compose files reference only the pinned image tags; `latest` is forbidden.
- springdoc 2.10.0 must be proven live at Phase 3 (Swagger UI and
  `/v3/api-docs` served) before any feature claim uses it.
