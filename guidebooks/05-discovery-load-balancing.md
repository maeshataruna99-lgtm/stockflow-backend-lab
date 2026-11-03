# Guidebook 05: Discovery-Based Load Balancing with Two Instances

## 1. Concept

Routes use `lb://CATALOG-SERVICE`. The gateway asks Eureka (through Spring Cloud LoadBalancer) for the live instances of that service name and picks one per request, round robin by default. No instance address is written in the gateway config. Adding an instance means starting another container that registers under the same service name.

## 2. Implementation

No gateway code was needed: the `lb://` routes from unit 5.1 already balance. This unit adds the proof.
- `LoadBalancingGatewayTest`: two WireMock servers registered as `CATALOG-SERVICE[0]` and `[1]` in the simple discovery client. 10 requests produce exactly 5 hits on each.
- `compose.labs.yaml`: opt-in override that adds `catalog-2` (same image, same database) and exposes the `metrics` endpoint on both catalog instances, internal network only, so the per-instance request count can be read.

## 3. Reproduction

Automated:

```bash
./mvnw --batch-mode test -Dtest=LoadBalancingGatewayTest
```

Live, from the repository root:

```bash
docker compose -f compose.yaml -f compose.labs.yaml up --build -d
```

The metrics endpoint needs an admin token since catalog verifies the JWT itself (ADR 0007): log in as root through the gateway and pass the token as shown. Check that Eureka lists two `UP` instances of `CATALOG-SERVICE` (`http://localhost:8761`). Read the count on each instance, send requests through the gateway, read the counts again:

```bash
docker compose -f compose.yaml -f compose.labs.yaml exec -T catalog wget -qO- "--header=Authorization: Bearer <admin token>" "http://localhost:8081/actuator/metrics/http.server.requests?tag=uri:/api/v1/products"
```

```bash
docker compose -f compose.yaml -f compose.labs.yaml exec -T catalog-2 wget -qO- "--header=Authorization: Bearer <admin token>" "http://localhost:8081/actuator/metrics/http.server.requests?tag=uri:/api/v1/products"
```

Requests are in `playground/http/gateway-load-balancing.http`. Remove the lab instance afterwards:

```bash
docker compose -f compose.yaml -f compose.labs.yaml rm -sf catalog-2
```

## 4. Failure path

Stop one instance (`docker compose -f compose.yaml -f compose.labs.yaml stop catalog-2`) and keep sending requests. The gateway keeps a cached instance list, so for a short time it still picks the stopped instance. Those requests fail and the circuit breaker (unit 5.4) answers 503. After the cache refreshes, all requests go to the surviving instance. Since unit 5.6, a failed GET is retried and the retry goes to the other instance (see guidebook 06); writes are not.

## 5. Trade-offs

- Round robin ignores load: a slow instance gets the same share as a fast one.
- Client-side balancing means each gateway instance has its own view and its own cache.
- A stale cache trades registry traffic for a window of failed calls after an instance dies.

## 6. Interview questions

- What is the difference between server-side and client-side load balancing?
- Why can a gateway still call an instance that has already stopped?
- Why must two service instances use the same service name but different instance ids?
- What would you add so a request that hit a dead instance is retried on the other one, and why only for idempotent requests?
