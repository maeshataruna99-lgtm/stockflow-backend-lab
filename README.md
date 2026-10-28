# StockFlow Backend Lab

Backend-only Java/Spring Boot microservices portfolio project: master-data
services (`catalog-service`, `warehouse-service`) behind an `api-gateway` with a
`discovery-server`, backed by PostgreSQL and Redis, built phase by phase under an
evidence-based acceptance protocol (see [ROADMAP.md](ROADMAP.md)).

Key documents:

- [ROADMAP.md](ROADMAP.md) - phased plan and acceptance gates.
- [docs/STATUS.md](docs/STATUS.md) - what actually exists and what was actually
  verified.
- [docs/architecture/system-overview.md](docs/architecture/system-overview.md) -
  services, ports and request flows.
- [docs/decisions/](docs/decisions) - pinned versions, data ownership, demo auth
  policy.

## Non-goals

- No frontend; interaction through REST APIs, OpenAPI/Swagger, Bruno or `.http`
  collections.
- No claims of production readiness.
- No secrets or real credentials in Git.
- No cross-service database queries or foreign keys; services own their data.
- No distributed ACID or exactly-once claims without a verifiable mechanism.
- No benchmark claims without reproducible measurements.

## Status (honest summary)

- Phase 0 - repository and design baseline: VERIFIED on 2026-10-28
  (document baseline only; evidence in [docs/STATUS.md](docs/STATUS.md)).
- Phases 1-10 (application code): NOT STARTED. There is no runnable service
  yet; build and run instructions appear as they get real verification.

## Planned prerequisites

- JDK 21 LTS (exact distribution pinned in Phase 1 build files).
- Docker with Compose v2 (used from Phase 2 onward).
- Maven Wrapper per application (no global Maven install required).

## Security caveats

- A demo root account may exist only in demo/local profiles, sourced from
  environment variables and stored as a strong hash
  (see [docs/decisions/0003-demo-auth.md](docs/decisions/0003-demo-auth.md)).
- `.env.example` contains placeholders only; never commit `.env`.
