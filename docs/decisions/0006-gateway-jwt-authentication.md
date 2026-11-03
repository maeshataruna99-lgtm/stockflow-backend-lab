# ADR 0006 - Gateway JWT Authentication and Role Authorization

Status: Accepted (2026-10-29, Phase 5 Unit 5.2 baseline). Fulfills the policy
mandate established in ADR 0003 regarding demo authentication and token issuance.

## Context

In Phase 5 Unit 5.1, dynamic routing was established to dispatch client traffic
arriving at the API Gateway to downstream services (`CATALOG-SERVICE` and
`WAREHOUSE-SERVICE`). However, routing was unauthenticated.

Phase 5 Unit 5.2 requires:
1. Perimeter authentication verifying JSON Web Tokens (JWT).
2. Role-based authorization enforcing least-privilege access rules across
   HTTP verbs and paths.
3. Strict isolation of demo/root authentication to non-production profiles
   as mandated by ADR 0003.
4. Downstream context propagation while preventing external header spoofing.

## Decision

### 1. Token Cryptographic Specification
- **Algorithm**: HMAC-SHA256 (`HS256`).
- **Secret Key**: Injected via `JWT_SECRET` environment variable (minimum 256
  bits / 32 bytes). In `dev`/`demo` profiles, a documented development fallback
  is provided if unset; in `prod` profile, a missing secret fails startup fast.
- **Claims**:
  - `sub`: username or account identifier (e.g. `root`, `demo-user`).
  - `roles`: array of granted authority strings (e.g. `["ROLE_ADMIN"]`,
    `["ROLE_USER"]`).
  - `iss`: issuer identifier (`stockflow-gateway`).
  - `iat`: issuance timestamp (`Instant.now()`).
  - `exp`: expiration timestamp (`iat + expirationSeconds`, default 3600 s).

### 2. Authorization Matrix
| Target Path Pattern | HTTP Method | Minimum Required Role | Unauthenticated | Insufficient Role |
| --- | --- | --- | --- | --- |
| `/actuator/health`, `/actuator/info` | `GET` | Public (no token needed) | `200 OK` | N/A |
| `/api/v1/auth/login` | `POST` | Public (non-prod profiles only) | `200 OK` / `401` | N/A |
| `/api/v1/**` (Products, Categories, Warehouses) | `GET` | `ROLE_USER` or `ROLE_ADMIN` | `401 Unauthorized` | `403 Forbidden` |
| `/api/v1/**` (Mutations) | `POST`, `PATCH`, `PUT`, `DELETE` | `ROLE_ADMIN` | `401 Unauthorized` | `403 Forbidden` |

### 3. Demo Root and Token Issuer Isolation (ADR 0003 Alignment)
- The demo login controller (`DemoAuthController`, `/api/v1/auth/login`) is
  annotated with `@Profile("!prod")`. Under the `prod` profile, the bean is
  not registered in the application context and requests return `404 Not Found`.
- Demo credentials (`ROOT_USERNAME` and `ROOT_PASSWORD`) are resolved from
  environment variables. Passwords are never stored or evaluated in plaintext;
  they are evaluated using BCrypt `PasswordEncoder`.
- No endpoint permits arbitrary public minting of tokens with user-chosen
  roles; only authenticated credentials produce signed tokens.

### 4. Downstream Context Propagation & Anti-Spoofing
- The reactive gateway filter (`JwtAuthenticationFilter`) strips any incoming
  client-supplied `X-User-Id` and `X-User-Roles` headers before routing.
- Upon cryptographic token verification, the gateway injects:
  - `X-User-Id`: extracted from the verified `sub` claim.
  - `X-User-Roles`: comma-separated string from the verified `roles` claim.
- Downstream services receive authenticated identity headers without needing
  direct coupling to JWT secret keys.

### 5. Error Contract (RFC 7807)
All authentication and authorization failures return structured
`application/problem+json` bodies:
- `401 Unauthorized`: token missing, expired, tampered signature, or malformed.
- `403 Forbidden`: authenticated subject lacks the required role.

## Consequences

- Gateway acts as the single security enforcement perimeter.
- Downstream services are protected from unauthenticated access and header
  spoofing when routed through the gateway.
- Automated tests must verify all 7 security dimensions (valid, expired,
  tampered, missing, insufficient role, admin access, prod-isolation).
- Production deployments cannot accidentally expose demo root credentials.
