# Guidebook 01: Dynamic Routing in API Gateway

## 1. Concept

In a microservices architecture, clients (web, mobile, third-party) should not communicate directly with dozens of internal services running on dynamic hostnames and ephemeral ports. The **API Gateway pattern** establishes a single, reverse-proxy entry point for all incoming external HTTP traffic.

**Dynamic routing** enables the API Gateway to decouple external client request paths from downstream physical server locations:
- Incoming client requests target semantic API paths such as `/api/v1/products` or `/api/v1/warehouses`.
- Instead of hardcoding target URLs (`http://10.0.1.12:8081`), the gateway uses discovery-aware URIs (`lb://CATALOG-SERVICE`, `lb://WAREHOUSE-SERVICE`).
- The reactive load balancer (`Spring Cloud LoadBalancer`) queries the service registry (`Eureka`) at runtime, dynamically resolving healthy service instances.

---

## 2. Implementation

In StockFlow Backend Lab, routing is defined via a dedicated, type-safe Java `@Configuration` bean (`GatewayRoutesConfig`), ensuring routes are verified at compile time and ready for subsequent filter chains (JWT auth, rate limiting, circuit breaker).

### Route Definition (`dev.stockflow.gateway.config.GatewayRoutesConfig`)

```java
@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouteLocator gatewayRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("catalog-products-route", r -> r
                        .path("/api/v1/products", "/api/v1/products/**")
                        .uri("lb://CATALOG-SERVICE"))
                .route("catalog-categories-route", r -> r
                        .path("/api/v1/categories", "/api/v1/categories/**")
                        .uri("lb://CATALOG-SERVICE"))
                .route("warehouse-route", r -> r
                        .path("/api/v1/warehouses", "/api/v1/warehouses/**")
                        .uri("lb://WAREHOUSE-SERVICE"))
                .build();
    }
}
```

### Architectural Safeguards Enforced
1. **Explicit Routing vs. Open Discovery Locator**: `spring.cloud.gateway.discovery.locator.enabled` is intentionally left disabled. Open locators automatically expose `/SERVICE-NAME/**`, which inadvertently exposes downstream `/actuator`, database consoles, or internal debugging endpoints.
2. **Strict Path Scoping**: Only public business endpoints (`/api/v1/products/**`, `/api/v1/categories/**`, `/api/v1/warehouses/**`) are exposed.
3. **Gateway Actuator Isolation**: Gateway's own actuator endpoints (`/actuator/health`, `/actuator/info`) are handled locally by the gateway without forwarding.

---

## 3. Reproduction & Automated Verification

### Automated In-Memory Test Suite
The gateway is tested without external Docker dependencies using `WebTestClient` and `spring-cloud-contract-wiremock` in `DynamicRoutingGatewayTest`:

```bash
# Execute unit/slice tests inside api-gateway
./mvnw.cmd test -Dtest=DynamicRoutingGatewayTest
```

### Test Coverage (9 automated assertions)
1. `GET /api/v1/products` $\rightarrow$ routed to `CATALOG-SERVICE`, HTTP 200.
2. `GET /api/v1/products/{id}` $\rightarrow$ routed to `CATALOG-SERVICE`, HTTP 200.
3. `POST /api/v1/products` $\rightarrow$ payload preserved and routed to `CATALOG-SERVICE`, HTTP 201.
4. `GET /api/v1/categories` $\rightarrow$ routed to `CATALOG-SERVICE`, HTTP 200.
5. `GET /api/v1/warehouses` $\rightarrow$ routed to `WAREHOUSE-SERVICE`, HTTP 200.
6. `GET /api/v1/warehouses/{id}/locations` $\rightarrow$ routed to `WAREHOUSE-SERVICE`, HTTP 200.
7. Gateway local actuator health remains local and responds HTTP 200 `{"status":"UP"}`.
8. Unmapped path `/api/v1/unknown` returns HTTP 404 from Gateway.
9. Attempting to traverse to downstream internal actuator (`/api/v1/products/actuator/env`) returns HTTP 404.

### Runnable Request Collection
See [playground/http/gateway-dynamic-routing.http](file:///c:/Users/Cinder/IdeaProjects/stockflow-backend-lab/playground/http/gateway-dynamic-routing.http) for manual execution with VS Code REST Client or IntelliJ HTTP client.

---

## 4. Failure Modes & Edge Cases

| Scenario | Symptom / Status | Gateway Behavior |
| --- | --- | --- |
| **No Route Matched** | `404 Not Found` | Gateway terminates the exchange immediately; no downstream network call is made. |
| **Service Down / Not Registered** | `503 Service Unavailable` | LoadBalancer cannot find any active instance for the service ID; returns `503` without leaking internal IP/stack trace. |
| **Downstream Actuator Probe** | `404 Not Found` | Request does not match predicate rules and is dropped at the perimeter. |

---

## 5. Architectural Trade-offs

- **Explicit Routes vs. Automated Discovery Locator**:
  - *Trade-off*: Explicit routes require manual configuration for each new endpoint prefix.
  - *Benefit*: Total perimeter control. Zero risk of leaking internal administrative or discovery APIs.
- **Java DSL vs. YAML Configuration**:
  - *Trade-off*: Java code requires compilation.
  - *Benefit*: Strong typing, IDE auto-completion, refactoring safety, and straightforward programmatic composition of filters (auth, rate limits, fallbacks).

---

## 6. Interview Questions

1. **How does Spring Cloud Gateway resolve `lb://SERVICE-NAME` without blocking?**
   Spring Cloud Gateway operates on Project Reactor / Netty (non-blocking). When encountering the `lb://` scheme, the `GatewayReactiveLoadBalancerClientFilter` delegates to `ReactiveLoadBalancer.choose()`, which queries `ServiceInstanceListSupplier` asynchronously and returns a `Mono<Response<ServiceInstance>>`.

2. **Why should `discovery.locator.enabled` be avoided in production environments?**
   Because it automatically creates routes for every service in the registry using the pattern `/{serviceId}/**`. This exposes internal endpoints such as `/catalog-service/actuator/env` or heapdumps to anyone who can reach the gateway port.

3. **What is the difference between Spring Cloud Gateway (reactive) and Netflix Zuul 1 (blocking)?**
   Zuul 1 uses traditional Servlet architecture with a thread-per-connection model (`blocking I/O`). Spring Cloud Gateway uses Reactor Netty with an event-loop model (`non-blocking I/O`), allowing it to handle tens of thousands of concurrent connections with minimal memory and thread overhead.
