# Guidebook 19: Connection Pooling (HikariCP, warehouse-service)

## 1. Concept

Opening a database connection is expensive (TCP, authentication, a backend process in PostgreSQL), so the application keeps a pool of open connections. A request borrows one for as long as its transaction runs and gives it back at the end. Two numbers decide behavior:

- Pool size: how many requests can use the database at the same moment. The request that finds every connection in use waits.
- Wait timeout (HikariCP `connection-timeout`): how long a request waits for a free connection before it fails. The HikariCP default is 30 s, so a saturated pool makes requests hang for 30 s, which is long past what any caller or gateway waits.

Throughput rises with the pool only while the database has capacity left. Queries that wait (disk, locks, network) overlap well; queries that burn CPU stop gaining once the pool is larger than the CPU can run in parallel, and a larger pool then only adds contention. The pool must also fit under PostgreSQL `max_connections` across all instances of all services.

## 2. Implementation (warehouse-service)

- `application.yml`: `spring.datasource.hikari.maximum-pool-size: 10` and `connection-timeout: 3000` (ms), explicit instead of the defaults (10 and 30 s). `DataSourceConfigTest` reads them from the file (the test `application.yml` shadows the main one).
- `GlobalExceptionHandler`: when no connection can be obtained for a transaction (`CannotCreateTransactionException`, `CannotGetJdbcConnectionException`), the answer is a `503` problem+json. Title "Database busy" when the root cause is HikariCP's `SQLTransientConnectionException` (pool timeout), "Database unavailable" for any other cause (database unreachable). The request did nothing, so the caller may retry.
- Lab only (behind `stockflow.lab.unsafe-stock`): `GET /api/v1/stock/lab/hold?millis=` (at most 10 s) keeps one connection busy with `pg_sleep`. `PoolLabService` does not exist without the property.
- Not changed: catalog-service and the other services still use the HikariCP defaults (30 s wait). Only the warehouse is bounded here.

## 3. Measured observation

3.1 Exhaustion through HTTP (`ConnectionPoolTest`, pool of 5, wait 1 s, 20 simultaneous requests that each hold a connection for 600 ms):

| Burst | 200 | 503 | 503 came after | Peak waiting (`hikaricp.connections.pending`) |
|---|---|---|---|---|
| Warm | 10 | 10 | 1018 to 1060 ms | 10 |
| Cold (first requests after the context started) | 10 | 10 | 1723 to 1843 ms | 15 |

The late requests fail after the configured 1 s plus a few tens of milliseconds, not after 30 s. The cold burst is slower because the first requests through MockMvc also pay for warm-up. After a burst the next request succeeds.

3.2 Lab stack (`labs/database/pool-lab.sh`, pool 10, wait 3 s, requests held 1000 ms, sent at the same moment):

| Requests | 200 | 503 | Wall time (includes about 1 s of `docker exec` overhead) |
|---|---|---|---|
| 10 | 10 | 0 | 3 s |
| 40 | 40 | 0 | 5 to 6 s (three runs) |
| 60 | 40 | 20 | 5 s (three runs, same split each time) |
| 40, held 100 ms | 40 | 0 | 2 s |
| 1, held 0 ms, right after the 60-request bursts | 1 | 0 | 1 s |

With 40 requests nothing failed: the last group of 10 waited about 3.0 s, right at the timeout, and got its connection. With 60 requests the fifth and sixth groups waited longer than 3 s and failed. Four groups of 10 fit into the 3 s wait, which is why the split was 40 and 20.

3.3 Pool size against throughput (`PoolSizingTest`, 32 client threads, fixed batch of queries, two runs of the class, times in ms for the whole batch):

| Query | Pool 1 | Pool 2 | Pool 4 | Pool 8 | Pool 16 |
|---|---|---|---|---|---|
| `pg_sleep(0.05)`, 160 queries, run 1 | 8404 | 4255 | 2177 | 1244 | 987 |
| same, run 2 | 8390 | 4216 | 2174 | 1252 | 991 |
| `count(*)` over `generate_series(1, 500000)`, 40 queries, run 1 | 5283 | 3327 | 2037 | 1801 | 1870 |
| same, run 2 | 5333 | 3273 | 1944 | 1724 | 1885 |

The JVM saw 8 CPU cores, and PostgreSQL ran in a Docker container on the same machine. The waiting query halves with each doubling up to 8 (the ideal for 160 queries of 50 ms is 8000, 4000, 2000, 1000, 500 ms; the measured pool 16 value of about 990 ms is above the ideal because of per-query overhead). The CPU query gains from 1 to 4, then 8 and 16 are the same within noise (1.7 to 1.9 s): more connections no longer help. A first, heavier run of the CPU query (120 queries over 1.5 million rows, one run) showed pool 16 slower than pool 8 (22005 ms against 14815 ms); the lighter batch above did not reproduce a slowdown, only the plateau.

3.4 Metric lag: `hikaricp.connections.active` still read 5 for up to 732 ms after the last reply of a burst. HikariCP 7.0.2 builds its metrics statistics with a 1 s refresh (`PoolStats` created with `SECONDS.toMillis(1)`, read from the class files), so a gauge can trail the real pool by up to a second. It was not a connection leak: the value always returned to 0, 53 and 732 ms after the last reply in the two bursts. Use `HikariPoolMXBean` for an exact instant count.

One run per row except where two runs are shown; one machine; not a benchmark.

## 4. Reproduction

Automated (real PostgreSQL container):

```bash
./mvnw --batch-mode test -Dtest=ConnectionPoolTest,PoolSizingTest,DataSourceConfigTest
```

Live (log in as root through the gateway and pass the token):

```bash
docker compose -f compose.yaml -f compose.labs.yaml up --build -d
```

```bash
labs/database/pool-lab.sh <admin-token> 60 1000
```

## 5. Failure modes and edge cases

- A pool that is too small makes requests queue; a pool that is too large moves the queue into PostgreSQL and can exceed `max_connections` (100 by default) once several instances run. Size it from the measured concurrency the database can serve, not from the number of request threads.
- A request that holds a connection while it waits for something slow (an HTTP call, `Thread.sleep`, user input) shrinks the effective pool for everyone. Keep transactions short; the catalog product read already releases the connection for a cache hit (guidebook 11).
- A 503 on pool timeout is a correct answer only if callers retry with a bound and a delay; a burst of immediate retries refills the queue.
- The lock waits of guidebooks 16 and 17 keep a connection busy while they wait, so a few blocked requests can exhaust a small pool even if the database itself is idle.
- `connection-timeout` limits the wait for a connection only. It does not limit a running query; that needs a statement timeout (not set here).

## 6. Interview questions

- Why is a pool of 100 connections often slower than a pool of 10?
- What happens to a request that finds the pool empty, and how long should it wait?
- How do pool size, instance count and PostgreSQL `max_connections` relate?
- Why can a metric for active connections lag the real value?
