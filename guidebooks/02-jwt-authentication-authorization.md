# Guidebook 02: JWT Authentication and Role Authorization in API Gateway

## 1. Concept

In a microservices architecture, authenticating users repeatedly in every downstream service introduces security vulnerabilities, code duplication, and tight coupling to identity providers.

The **Perimeter Authentication Pattern** places authentication and role-based authorization at the API Gateway:
- The API Gateway acts as the single security perimeter.
- Clients send an industry-standard `Authorization: Bearer <jwt-token>` header.
- The Gateway cryptographically verifies token integrity (HMAC-SHA256 signature, expiry, issuer) using non-blocking reactive filters.
- **Least-Privilege RBAC**:
  - Read requests (`GET /api/v1/**`) require `ROLE_USER` or `ROLE_ADMIN`.
  - Mutating requests (`POST`, `PATCH`, `PUT`, `DELETE /api/v1/**`) require `ROLE_ADMIN`.
- **Anti-Spoofing Context Propagation**:
  - External requests attempting to inject `X-User-Id` or `X-User-Roles` are stripped before downstream routing.
  - The Gateway injects verified `X-User-Id` and `X-User-Roles` headers for downstream services to consume without needing cryptographic keys.

---

## 2. Implementation

### Architecture Overview

```
Client (Browser / HTTP)
       │
       ▼ (Authorization: Bearer <JWT>)
[API Gateway (Spring Cloud Gateway)]
       │
       ├── 1. Path Matcher: Is path public? (e.g. /actuator/health, /api/v1/auth/login)
       ├── 2. Anti-Spoofing: Strip incoming X-User-Id / X-User-Roles
       ├── 3. Token Validator: Validate HS256 signature & expiration
       │        └── Invalid/Expired ──► 401 Unauthorized (RFC 7807 ProblemDetail)
       ├── 4. Role Authorizer: Verify ROLE_USER / ROLE_ADMIN
       │        └── Insufficient Role ──► 403 Forbidden (RFC 7807 ProblemDetail)
       └── 5. Context Injection: Inject verified X-User-Id and X-User-Roles
              │
              ▼ (Internal StockFlow Network)
       [Downstream Services (catalog-service, warehouse-service)]
```

### Key Components

1. **`JwtService`** (`dev.stockflow.gateway.security.JwtService`):
   Uses JJWT (`io.jsonwebtoken`) to generate and parse signed JWT tokens. Enforces minimum 256-bit key entropy for HMAC-SHA256.
2. **`JwtAuthenticationFilter`** (`dev.stockflow.gateway.security.JwtAuthenticationFilter`):
   A `GlobalFilter` running with `Ordered.HIGHEST_PRECEDENCE + 10` before routing filters. Validates tokens, checks HTTP verb against roles, and writes RFC 7807 `ProblemDetail` responses.
3. **`DemoAuthController`** (`dev.stockflow.gateway.security.DemoAuthController`):
   Gated with `@Profile("!prod")` per [ADR 0003](file:///c:/Users/Cinder/IdeaProjects/stockflow-backend-lab/docs/decisions/0003-demo-auth.md) and [ADR 0006](file:///c:/Users/Cinder/IdeaProjects/stockflow-backend-lab/docs/decisions/0006-gateway-jwt-authentication.md). Hashes credentials with BCrypt `PasswordEncoder` and exposes `/api/v1/auth/login`. Under `prod` profile, this bean does not exist and returns `404 Not Found`.

---

## 3. Reproduction & Automated Verification

### Automated In-Memory Test Suites

All tests run without requiring external Docker daemons, using Spring Cloud Contract WireMock and WebTestClient:

```bash
# Run security tests inside api-gateway
./mvnw.cmd test -Dtest=JwtAuthenticationGatewayTest,ProductionProfileSecurityTest
```

### Test Coverage (10 Automated Assertions)
1. `GET /api/v1/products` with valid `ROLE_USER` token $\rightarrow$ `200 OK` (WireMock verifies downstream receives `X-User-Id` and `X-User-Roles`).
2. `POST /api/v1/products` with `ROLE_USER` token $\rightarrow$ `403 Forbidden` (`application/problem+json`).
3. `POST /api/v1/products` with `ROLE_ADMIN` token $\rightarrow$ `201 Created` (admin allowed to mutate).
4. Missing `Authorization` header on `/api/v1/products` $\rightarrow$ `401 Unauthorized` (`ProblemDetail`).
5. Expired token on `/api/v1/products` $\rightarrow$ `401 Unauthorized` (`ProblemDetail`).
6. Tampered signature on `/api/v1/products` $\rightarrow$ `401 Unauthorized` (`ProblemDetail`).
7. Header anti-spoofing check: client-sent `X-User-Id: attacker` is purged and replaced with authenticated subject `real-user`.
8. Demo login with root credentials $\rightarrow$ `200 OK` with valid access token.
9. Demo login with wrong password $\rightarrow$ `401 Unauthorized`.
10. Production profile isolation (`ProductionProfileSecurityTest`): `POST /api/v1/auth/login` returns `404 Not Found` when `spring.profiles.active=prod`.

### Runnable Request Collection
See [playground/http/gateway-jwt-auth.http](file:///c:/Users/Cinder/IdeaProjects/stockflow-backend-lab/playground/http/gateway-jwt-auth.http) for manual execution with VS Code REST Client or IntelliJ HTTP client.

---

## 4. Failure Modes & Edge Cases

| Scenario | HTTP Status | Response Contract | Security Guarantee |
| --- | --- | --- | --- |
| **No Token Sent** | `401 Unauthorized` | `application/problem+json` | Request halted at perimeter; downstream is never invoked. |
| **Expired Token** | `401 Unauthorized` | `application/problem+json` | Replay attacks with stale tokens are rejected. |
| **Forged Signature** | `401 Unauthorized` | `application/problem+json` | Cryptographic signature validation failure prevents privilege escalation. |
| **User Role on Mutation** | `403 Forbidden` | `application/problem+json` | Regular users cannot mutate catalog or warehouse master data. |
| **Spoofed Identity Header** | Sanitized / Overwritten | Header value replaced | Downstream trust is preserved; client cannot impersonate other users. |
| **Login in Production** | `404 Not Found` | Standard 404 response | Demo root account cannot be minted or probed in production environments. |

---

## 5. Architectural Trade-offs

- **Perimeter Auth vs. Per-Service Token Verification**:
  - *Trade-off*: Downstream services must trust the gateway (requires secure internal network).
  - *Benefit*: Dramatically lower latency and CPU overhead; downstream services avoid redundant asymmetric/symmetric cryptographic verification on every microservice hop.
- **Header Propagation (`X-User-Id`) vs. mTLS**:
  - *Trade-off*: In-transit identity within internal Docker network relies on network isolation.
  - *Benefit*: Lightweight, zero-certificate setup, easy observability in logs and tracing headers.

---

## 6. Interview Questions

1. **Why is header sanitization (`headers.remove("X-User-Id")`) critical when the Gateway propagates user context?**
   If the gateway does not strip incoming headers, an unauthenticated or low-privilege external client could send `X-User-Id: admin` or `X-User-Roles: ROLE_ADMIN`. If a downstream service trusts this header, it would lead to complete privilege escalation.

2. **How does Spring Cloud Gateway validate JWTs reactively without blocking threads?**
   Traditional Spring Security blocks threads during database/auth calls. In Spring Cloud Gateway, JWT signature verification using HMAC-SHA256 (`HS256`) is purely CPU-bound (in-memory math without network I/O). It executes inside Project Reactor's non-blocking chain without tying up Netty worker threads.

3. **What is the purpose of RFC 7807 (`ProblemDetail`) in API security?**
   RFC 7807 standardizes error responses across microservices (`type`, `title`, `status`, `detail`, `instance`). It prevents disparate error formats and ensures that security rejections (401/403) provide actionable error information without leaking internal stack traces or cryptographic keys.
