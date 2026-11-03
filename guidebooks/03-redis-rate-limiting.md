# Guidebook 03: Redis-Backed Distributed Rate Limiting in API Gateway

## 1. Concept

In high-scale microservices architectures, exposing APIs without traffic throttling leaves downstream services vulnerable to Denial of Service (DoS), brute-force credential stuffing, noisy-neighbor tenant exhaustion, and cascading database overloads.

The **Distributed Rate Limiting Pattern** delegates traffic governance to the API Gateway tier using a centralized in-memory datastore (Redis):
- **Token Bucket Algorithm**: Standardized rate-limiting algorithm that maintains a bucket with a maximum capacity (`burstCapacity`). Tokens replenish at a constant rate (`replenishRate` tokens/sec). Each request consumes `requestedTokens` (typically 1). If tokens are available, the request passes; if the bucket is empty, the request is throttled immediately.
- **Distributed State Synchronization**: In-memory rate limiting (e.g., local Guava or Bucket4j in a single JVM) fails when running multiple Gateway instances behind a load balancer, because quota is fragmented across instances. Redis provides atomic, thread-safe counter operations (using non-blocking Redis Lua scripts) shared across all Gateway instances.
- **Anti-Spoofing Identity Resolution**:
  - A secure rate limiter **must not** trust arbitrary client-supplied headers such as `X-User-Id` or spoofed `X-Forwarded-For`.
  - For **authenticated requests**, the key is resolved from the cryptographically verified JWT subject: `user:<userId>`.
  - For **unauthenticated or public requests**, the key falls back to the verified TCP socket address: `ip:<remoteAddress>`.
- **Standard HTTP Throttling Contract**:
  - Allowed requests pass through to downstream services with rate limit telemetry headers: `X-RateLimit-Replenish-Rate`, `X-RateLimit-Burst-Capacity`, `X-RateLimit-Remaining`.
  - Exceeded requests receive **`429 Too Many Requests`** immediately at the gateway perimeter. Downstream services and databases are completely shielded.

---

## 2. Implementation & Architecture

### System Flow Diagram

```
Incoming Request (Client)
       │
       ▼
[API Gateway (Spring Cloud Gateway)]
       │
       ├── 1. Path Matcher & Security Filter (JwtAuthenticationFilter)
       │        └── Validates JWT, sets internal exchange attribute: "stockflow.authenticated.user"
       │
       ├── 2. Key Resolution (UserOrIpKeyResolver)
       │        ├── Has authenticated user attribute? ──► "user:<userId>"
       │        └── Else fallback to remote IP ──────────► "ip:<remoteIp>"
       │            (Client-sent X-User-Id header is completely ignored)
       │
       ├── 3. Token Bucket Evaluation (RedisRateLimiter / Lua Script in Redis)
       │        ├── Tokens available in bucket?
       │        │     ├── YES ──► Decrement token, inject X-RateLimit-* headers, forward to downstream
       │        │     └── NO  ──► Abort request immediately ──► Return 429 Too Many Requests
       │
       ▼ (Only when quota allowed)
[Downstream Services (catalog-service, warehouse-service)]
```

### Key Components

1. **`RateLimitProperties`** (`dev.stockflow.gateway.ratelimit.RateLimitProperties`):
   Binds configuration from `stockflow.gateway.rate-limit`:
   - `replenish-rate`: 10 tokens/sec
   - `burst-capacity`: 20 tokens
   - `requested-tokens`: 1 token per call
2. **`UserOrIpKeyResolver`** (`dev.stockflow.gateway.ratelimit.UserOrIpKeyResolver`):
   Implements Spring Cloud Gateway's `KeyResolver`. Resolves reactive `Mono<String>` by prioritizing the verified exchange attribute `stockflow.authenticated.user`, falling back to `exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()`.
3. **`RateLimiterConfig`** (`dev.stockflow.gateway.ratelimit.RateLimiterConfig`):
   Configures the reactive `RedisRateLimiter` bean with `@ConditionalOnMissingBean(RateLimiter.class)`, allowing test environments to supply an in-memory deterministic rate limiter without needing a live Redis server.
4. **`GatewayRoutesConfig`** (`dev.stockflow.gateway.config.GatewayRoutesConfig`):
   Wires `requestRateLimiter` filter with `setStatusCode(HttpStatus.TOO_MANY_REQUESTS)` onto all protected routing paths (`/api/v1/products/**`, `/api/v1/categories/**`, `/api/v1/warehouses/**`).
5. **Infrastructure Orchestration** (`compose.yaml`):
   Configures `api-gateway` to link to the shared `redis` service with `SPRING_DATA_REDIS_HOST: redis` and `SPRING_DATA_REDIS_PORT: 6379`.

---

## 3. Reproduction & Automated Verification

### Automated In-Memory Test Suites

To ensure rapid, deterministic CI/CD execution without external Docker dependencies, tests use an in-memory controllable `RateLimiter` via `TestRateLimiterConfig`:

```bash
# Execute Rate Limiting and Key Resolver test suites
./mvnw.cmd test -Dtest=RateLimitingGatewayTest,UserOrIpKeyResolverTest
```

### Verified Test Cases (6 Assertions)

1. **`UserOrIpKeyResolverTest` (Key Resolution & Anti-Spoofing)**:
   - `resolve_withAuthenticatedUser_shouldReturnUserKey`: Verified user attribute `user-123` resolves to `user:user-123`.
   - `resolve_withoutAuthenticatedUser_shouldFallbackToRemoteIp`: Unauthenticated request resolves to `ip:192.168.1.100`.
   - `resolve_withUnauthenticatedRequestContainingSpoofedHeader_shouldIgnoreHeaderAndUseIp`: Malicious client sending `X-User-Id: attacker-spoofed` is ignored; resolver safely returns `ip:10.0.0.5`.
   - `resolve_withoutRemoteAddress_shouldReturnAnonymousKey`: Safe fallback to `ip:anonymous` when socket remote address is unavailable.

2. **`RateLimitingGatewayTest` (HTTP Gateway Integration)**:
   - `requestWithinQuota_shouldSucceedWithRateLimitHeaders`:
     - Calls `/api/v1/products`.
     - Expects `200 OK`.
     - Validates response headers: `X-RateLimit-Replenish-Rate: 10`, `X-RateLimit-Burst-Capacity: 20`, `X-RateLimit-Remaining: 19`.
     - Verifies WireMock downstream catalog-service receives the request exactly once.
   - `requestExceedingQuota_shouldReturn429TooManyRequests`:
     - Simulates quota exhaustion (`TestRateLimiterConfig.EXCEED_QUOTA = true`).
     - Calls `/api/v1/products`.
     - Expects HTTP status `429 Too Many Requests`.
     - Verifies WireMock downstream service was **never invoked** (zero interactions).

### Runnable Request Collection

See [playground/http/gateway-rate-limiting.http](file:///c:/Users/Cinder/IdeaProjects/stockflow-backend-lab/playground/http/gateway-rate-limiting.http) for interactive testing against a running Gateway and Redis instance.

---

## 4. Failure Modes & Edge Cases

| Scenario | Behavior / Consequence | Gateway Mitigation Strategy |
| --- | --- | --- |
| **Burst Traffic Spikes** | Traffic exceeds replenish rate but stays within burst capacity. | Token bucket absorbs bursts up to `burstCapacity` (20 tokens) without throttling. |
| **Sustained DoS Attack** | Requests arrive faster than replenish rate after burst tokens are spent. | Immediate `429 Too Many Requests` issued by gateway; downstream CPU and DB stay safe. |
| **Spoofed Identity Header** | Attacker sends `X-User-Id: victim-account` to deplete victim's quota. | `UserOrIpKeyResolver` only reads from internal authenticated exchange attributes, completely ignoring raw client headers. Attacker's own IP is throttled instead. |
| **Shared Public NAT / Proxy IP** | Thousands of office users share one public IP address. | Authenticated requests resolve to `user:<userId>`, giving each user their own dedicated bucket regardless of shared IP. |
| **Redis Outage / Unavailability** | Redis connection drops or times out. | Verified in Phase 6: `spring.data.redis.timeout` and `connect-timeout` are 300 ms, and `RedisRateLimiter` fails open, so the request is let through after about 0.3 s. Before those settings existed, every request waited about 60 s (Lettuce default) and then passed; the rate limit is not enforced while Redis is down. |

---

## 5. Architectural Trade-offs

- **Redis Token Bucket vs. Local In-Memory Limiting (Guava/Bucket4j)**:
  - *Trade-off*: Redis adds a ~1ms network hop per request for rate-limit evaluation.
  - *Benefit*: Unified global quota across horizontally autoscaled Gateway nodes. Local limiters allow clients to multiply their allowed quota by the number of gateway replicas (e.g. 5 replicas $\times$ 10 req/s = 50 req/s actual traffic).
- **Token Bucket vs. Leaky Bucket vs. Sliding Window Counter**:
  - *Token Bucket*: Allows controlled bursts while enforcing an average rate. Best fit for REST APIs where web pages load multiple resources in quick succession.
  - *Leaky Bucket*: Enforces a perfectly smooth output rate; drops or queues bursts. Can introduce unnecessary latency for benign client bursts.
  - *Sliding Window Counter*: Highly accurate boundary tracking, but higher memory footprint in Redis compared to Token Bucket's two-counter state (timestamp + token count).

---

## 6. Interview Questions & Senior Insights

1. **How does Spring Cloud Gateway execute Redis rate limiting without blocking reactive Netty worker threads?**
   Spring Cloud Gateway uses Spring Data Redis Reactive (`ReactiveStringRedisTemplate` / Lettuce client), which connects via non-blocking Netty sockets. The token bucket algorithm is executed on Redis via a single non-blocking Lua script (`request_rate_limiter.lua`). The result returns as a Project Reactor `Mono<Response>`, ensuring that no gateway thread blocks while waiting for Redis.

2. **Why is using client IP alone for rate limiting problematic, and how does StockFlow address it?**
   IP-only rate limiting suffers from two major flaws:
   1. *NAT Collisions*: Corporate offices, university campuses, or mobile carrier gateways route thousands of distinct users through a single public IP. Throttling that IP unfairly blocks legitimate users.
   2. *IP Rotation / Botnets*: Distributed attackers cycle through botnet IP addresses, bypassing IP-based rate limits easily.
   StockFlow solves this using `UserOrIpKeyResolver`: authenticated users receive an individualized bucket keyed by `user:<userId>`, while unauthenticated endpoints fall back to `ip:<remoteAddress>`.

3. **What is the mathematical relationship between `replenishRate` and `burstCapacity` in Token Bucket?**
   - `replenishRate` defines the sustained steady-state throughput (e.g., 10 requests per second allowed continuously).
   - `burstCapacity` defines the maximum allowable bucket capacity (e.g., 20 tokens). It dictates how many simultaneous requests can be accommodated after an idle period before throttling kicks in.
   - If a client is idle for $\ge 2$ seconds, the bucket fills to 20 tokens. The client can immediately fire 20 requests in 100ms (burst). Request 21 will be rejected with 429 until the bucket replenishes 1 token in the next 100ms.
