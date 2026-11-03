# ADR 0007 - Catalog and warehouse verify the JWT themselves

Status: Accepted (2026-11-03). Completes ADR 0003 and ADR 0006.

## Context

ADR 0003 and ROADMAP section 2 require every service to enforce authorization
where appropriate. After the Phase 5 gateway work only the gateway checked the
token. Catalog and warehouse had no security code and trusted the network:
they are not published to the host, but any container on the `stockflow`
network could call them without a token. The Phase 5 gate review listed this
as an open item.

## Decision

- Catalog and warehouse validate the same JWT the gateway issues, using Spring
  Security's OAuth2 resource server (`spring-boot-starter-oauth2-resource-server`,
  version managed by the Spring Boot BOM). The gateway forwards the
  `Authorization` header unchanged.
- Algorithm and key: HS256 with the shared secret `JWT_SECRET`
  (`stockflow.security.jwt.secret` in each service, no default; the service
  fails to start without a key of at least 32 bytes). The issuer must be
  `stockflow-gateway`; expiry is checked.
- Rule, the same as at the gateway: `GET /api/v1/**` needs `ROLE_USER` or
  `ROLE_ADMIN`; every other method on `/api/v1/**` needs `ROLE_ADMIN`.
  Open without a token: `/actuator/health/**`, `/actuator/info`, `/v3/api-docs/**`,
  `/swagger-ui/**` (container health checks have no token; the documents
  describe the API shape only). Everything else is denied.
- Identity headers such as `X-User-Id` and `X-User-Roles` are not trusted by
  the services; roles come only from the verified token.
- Errors are RFC 7807 `application/problem+json` (401 and 403) like the rest of
  the service.
- The code is duplicated in the two services (a `security` package of two
  classes each) because the ROADMAP avoids shared Java libraries until a
  concrete need exists.

## Consequences

- A caller that bypasses the gateway still needs a valid token and the right
  role.
- One symmetric secret is now held by three services. A leak of any one lets
  its holder mint tokens; moving to an asymmetric key (services hold only the
  public key) is the upgrade path and is not done.
- Running a service on the host (`mvnw spring-boot:run`) needs
  `STOCKFLOW_SECURITY_JWT_SECRET` in its environment.
- The gateway must sign with HS256 explicitly. JJWT otherwise chooses the
  algorithm from the key length (HS384 for 48-63 bytes, HS512 from 64 bytes),
  which the services reject. This was found by a live run on 2026-11-03 and
  fixed in `JwtService`.
- Service tests sign their own HS256 tokens (`TestTokens`); a token built by
  the gateway code itself is only exercised in the live check, not in the
  services' unit tests.
