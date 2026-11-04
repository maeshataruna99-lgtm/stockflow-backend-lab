# Guidebook 27: Graceful Shutdown

## 1. Concept

When an instance is replaced (a deploy, a scale-down, a node drain) the orchestrator sends it SIGTERM and, after a grace period, SIGKILL. What the process does between the two decides what the users see:
- Abrupt: the process dies with requests in flight; those clients get a reset connection or a timeout, and any write that was half done depends on the database to roll it back.
- Graceful: the instance stops accepting new connections and reports itself as not ready, lets the requests already in progress finish (up to a limit), then exits.

For graceful shutdown to work end to end three things have to agree: the application drains its requests, the grace period of the platform is longer than the drain, and the load balancer stops sending new requests to the instance quickly.

## 2. Implementation

- `server.shutdown: graceful` and `spring.lifecycle.timeout-per-shutdown-phase: 20s` in the `application.yml` of the four services. On shutdown the web server stops accepting new connections and waits up to 20 s for active requests.
- Readiness flips: the application publishes `ReadinessState.REFUSING_TRAFFIC` when the shutdown starts (guidebook 26).
- `stop_grace_period: 30s` for the services in `compose.yaml` and `compose.labs.yaml` (Docker's default is 10 s, shorter than the drain).
- Lab only: the warehouse endpoint `GET /api/v1/stock/lab/hold?millis=` (behind `stockflow.lab.unsafe-stock`) keeps a request in flight on purpose.

## 3. Measured observation

3.1 In one JVM (`GracefulShutdownTest`): a second instance of the warehouse is started against the test database; a request that holds a database connection for 3 s is sent to it over real HTTP; 0.7 s later the application context is closed (the same path a SIGTERM takes).

| Mode | In-flight request | Time of the request | `close()` took | A new request during the drain | Availability events |
|---|---|---|---|---|---|
| graceful, run 1 | 200 | 3073 ms | 2355 ms | refused | CORRECT, ACCEPTING_TRAFFIC, REFUSING_TRAFFIC |
| graceful, run 2 | 200 | 3064 ms | 2412 ms | refused | same |
| immediate, run 1 | `SocketException` (no response) | 3634 ms | 2941 ms | refused | same |
| immediate, run 2 | `SocketException` (no response) | 3386 ms | 2697 ms | refused | same |

With graceful shutdown the shutdown waited for the request (about 2.4 s of the 3 s were left) and the client got its 200. With the immediate mode the connection was closed under the client. The immediate `close()` also took 2.7 to 2.9 s, because the thread inside the JDBC call had to finish before the pool could be closed; the difference is what the client receives, not how long the close takes.

3.2 In containers (`labs/resilience/shutdown-lab.sh`, 10 requests of 6 s in flight in the warehouse, the container stopped 1.5 s later):

| Stop | Requests answered 200 | Container stopped after |
|---|---|---|
| `docker stop` (SIGTERM) | 10 of 10 (both runs) | 9 s |
| `docker kill` (SIGKILL) | 0 of 10 (both runs) | 1 s |

The 9 s are the remaining time of the 6 s requests plus the JVM shutdown, measured in whole seconds.

3.3 The configuration is checked in the real file: `GracefulShutdownTest.configurationIsGraceful` reads `src/main/resources/application.yml`.

## 4. Reproduction

In `apps/warehouse-service`:

```bash
./mvnw --batch-mode test -Dtest=GracefulShutdownTest
```

Live (log in as root through the gateway and pass the token; this stops and restarts the warehouse container):

```bash
labs/resilience/shutdown-lab.sh <admin-token> 10
```

## 5. Failure modes and edge cases

- The gateway and the registry: after SIGTERM the instance should leave Eureka (not checked here), but the gateway keeps its cached list for up to the refresh interval and can still send new requests to the draining instance, which refuses them (the drain refused a new request in the test above). A `GET` is then retried (unit 5.6), a write is not. A short pause between deregistration and closing the server removes the gap; not done and not tested here.
- A request that outlasts the 20 s timeout is cut off after it; choose the timeout from the slowest request you accept.
- The grace period of the platform must be longer than the drain; with Docker's default 10 s the same configuration would be killed before it finished.
- Background work (schedulers, message consumers) must stop taking new work during the drain; there is none in these services except the hourly purge of idempotency keys.
- A health check that stays UP during the drain keeps traffic arriving; here readiness flips first.

## 6. Interview questions

- What happens to a request that is in flight when the process gets SIGTERM, with and without graceful shutdown?
- Why must the platform's grace period be longer than the application's drain timeout?
- Why is a gap between "removed from the load balancer" and "stopped accepting connections" a problem?
- How do you test graceful shutdown without waiting for a deploy?
