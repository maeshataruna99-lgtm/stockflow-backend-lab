# Guidebook 26: Liveness and Readiness Probes

## 1. Concept

Two different questions are asked of a running service, and one health endpoint cannot answer both:
- Liveness: is the process alive and able to make progress? If not, the right action is to restart it. A liveness check must not depend on other systems: if it fails because the database is down, restarting the service does not help, and a restart of every instance at once turns a database outage into a full outage.
- Readiness: can this instance serve traffic right now? If not, the right action is to stop sending requests to it and wait. Readiness may depend on what the service needs to answer requests (its database), and it must also turn off by itself when the service is shutting down (guidebook 27).

A service that reports itself healthy while its database is gone sends users into errors; a service that reports itself dead because a dependency is down gets restarted for nothing.

## 2. Implementation

All four services (`catalog-service`, `warehouse-service`, `api-gateway`, `discovery-server`) have `management.endpoint.health.probes.enabled: true`, which adds `/actuator/health/liveness` and `/actuator/health/readiness`:
- Liveness group: `livenessState` only.
- Readiness group: `readinessState` and, for catalog and warehouse, `db`. Redis is not in the catalog's readiness: it is only a cache (unit 6.1), and the catalog answers from the database when Redis is down. The gateway and the discovery server have no database.
- The groups are set in `application.yml` and mirrored in `src/test/resources/application.yml` of catalog, warehouse and gateway (the test file shadows the main one); `ProbeConfigTest` reads the real file.
- The probe paths are open without a token in each service. The gateway needed a change for this: its JWT filter only exempted the exact path `/actuator/health`, so `/actuator/health/liveness` would have answered 401 to an orchestrator.
- Compose health checks now call `/actuator/health/readiness` (Docker has one health status, and it is used for start order and for "is this ready"). Docker does not restart an unhealthy container; `restart: unless-stopped` acts on a process exit only.

## 3. Measured observation

| Check | Result |
|---|---|
| Groups (`HealthProbesTest`, warehouse and catalog) | liveness does not contain `db`; readiness does; the catalog's readiness does not contain `redis` |
| Probes without a token (warehouse, catalog, gateway tests) | 200 `{"status":"UP"}` for both |
| Warehouse test, PostgreSQL container frozen with `docker pause` (connections hang), Hikari wait shortened to 1.5 s | readiness 503 `DOWN` after 2460 ms; liveness 200 `UP`; after the unfreeze readiness is 200 again |
| Live (`labs/resilience/probes-lab.sh`), the compose PostgreSQL frozen | warehouse and catalog: liveness 200 and readiness 503 while frozen; both 200 again after the unfreeze; database up at the start: 200 and 200 |
| Compose health checks | all four services reached `healthy` with the readiness endpoint as the check |

One run of the warehouse test and one of the live lab. The 2460 ms is bounded by the shortened Hikari waits of that test; with the production settings (3 s connection wait) a check takes longer to fail.

## 4. Reproduction

In `apps/warehouse-service`:

```bash
./mvnw --batch-mode test -Dtest=HealthProbesTest,ProbeConfigTest
```

Live:

```bash
labs/resilience/probes-lab.sh
```

## 5. Failure modes and edge cases

- The Eureka registry does not use these probes (the Eureka client's own health check is off by default), so a service whose readiness is DOWN still shows as UP in Eureka and the gateway can still route to it. Connecting readiness to the registry is not done and was not tested.
- A readiness check that queries the database on every call adds load; Spring's `db` indicator runs a trivial validation query. Orchestrators call probes every few seconds.
- A frozen database makes the check wait for the connection timeout before it fails; a refused connection fails at once.
- A liveness check that is too strict (for example one that depends on a thread pool) can kill a busy but healthy service.
- `diskSpace` and other indicators stay in the aggregate `/actuator/health` but not in either group; the aggregate is no longer used by Compose.

## 6. Interview questions

- What is the difference between liveness and readiness, and what should each one depend on?
- Why should a database outage make readiness fail but not liveness?
- What happens if every instance's liveness check depends on the shared database?
- Why does readiness have to flip when the service starts shutting down?
