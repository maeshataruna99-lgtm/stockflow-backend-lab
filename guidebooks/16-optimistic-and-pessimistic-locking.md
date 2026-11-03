# Guidebook 16: Optimistic and Pessimistic Locking (warehouse stock)

## 1. Concept

The read-modify-write of guidebook 15 loses updates because nothing stops two transactions from working on the same old value. There are two ways to stop it.

- Pessimistic locking: lock the row when you read it (`SELECT ... FOR UPDATE`). Other transactions that want the row wait until the first one commits or rolls back. No conflict can happen; the cost is waiting, and a lock that is held too long blocks everyone behind it.
- Optimistic locking: do not lock. Read the row together with a version number, compute, and write with `WHERE version = <the version I read>`, adding 1 to the version. If someone else wrote in between, the `WHERE` matches 0 rows: the writer knows it lost, re-reads and tries again. The cost is retries, and under heavy contention on one row most of the work is repeated.

Both are weaker tools than the single atomic `UPDATE ... WHERE quantity >= :n` of guidebook 15 when the rule fits in one statement. They matter when the rule needs Java code between the read and the write.

## 2. Implementation (warehouse-service)

- Flyway V4 adds `stock_levels.version BIGINT NOT NULL DEFAULT 0`. Every writer of the quantity (the atomic withdraw and the optimistic write) adds 1.
- `StockService.withdrawOptimistic`: each attempt is its own transaction: read quantity and version, check stock, `UPDATE ... SET quantity = :new, version = version + 1 WHERE id = :id AND version = :version`. 0 rows means a lost race, and the next attempt re-reads. At most `stockflow.stock.optimistic-attempts` (default 5) attempts, then `ConcurrentUpdateException`, a 409 "Concurrent update" that is safe to retry. Each re-attempt increments the counter `warehouse.stock.retries{lock=optimistic}`.
- `StockService.withdrawPessimistic`: one transaction. `SELECT set_config('lock_timeout', '2000ms', true)` (PostgreSQL, local to the transaction; `stockflow.stock.lock-timeout`, default 2 s) then `findByIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`), check, write. A wait longer than the timeout fails with `PessimisticLockingFailureException`, a 503 "Row locked".
- The `version` field of the `StockLevel` entity is read-only (`insertable = false, updatable = false`) and is not a JPA `@Version`. A JPA `@Version` would make the unsafe JPA save of guidebook 15 fail with an optimistic lock exception, which would silently fix the lab bug. Real code that uses JPA would normally use `@Version` and let Hibernate generate the guarded UPDATE; here the SQL is written out to show the mechanism.
- Endpoints `POST /api/v1/stock/{id}/withdraw-optimistic` and `/withdraw-pessimistic` exist only when `stockflow.lab.unsafe-stock=true` (lab file). The real endpoint `/withdraw` keeps the atomic UPDATE.

## 3. Measured observation

Starting stock 100, 100 simultaneous requests of 1 unit unless stated. "Accepted" is HTTP 200. Optimistic is limited to 5 attempts per request.

| Run | Endpoint | Accepted | Refused or gave up | Left | Accepted + left |
|---|---|---|---|---|---|
| Test (16 threads) | optimistic | 54 | 46 gave up (275 retries) | 46 | 100 |
| Lab stack, run 1 | optimistic | 46 | 54 gave up | 54 | 100 |
| Lab stack, run 2 | optimistic | 52 | 48 gave up | 48 | 100 |
| Lab stack, run 3 | optimistic | 52 | 48 gave up | 48 | 100 |
| Lab stack, stock 50 | optimistic | 44 | 56 (not enough stock or gave up) | 6 | 50 |
| Test | pessimistic | 100 | 0 | 0 | 100 |
| Lab stack, runs 1 to 3 | pessimistic | 100 | 0 | 0 | 100 |
| Lab stack, stock 50 | pessimistic | 50 | 50 (409 not enough stock) | 0 | 50 |
| Lab stack, runs 1 to 3 | unsafe (guidebook 15) | 100 | 0 | 91, 88, 92 | 191, 188, 192 |
| Lab stack, runs 1 to 3 | atomic UPDATE | 100 | 0 | 0 | 100 |

What the numbers show:
- Both strategies keep accepted + left equal to the starting stock: no update is lost. The unsafe path does not.
- Optimistic under this contention (100 requests on one row) gives up about half of the requests after 5 tries. It is correct and safe to retry, but it does not do the work. With one hot row, it is the wrong tool.
- Pessimistic accepts everything, one request at a time. With stock 50, 6 units stayed in an optimistic run because requests gave up while stock remained.
- In the lock timeout test a withdrawal blocked by a lock held in another transaction gave up after 2040 ms with the default 2 s timeout.

One run per row (three for the lab with stock 100), one machine, not a benchmark. The exact numbers change from run to run, the invariants did not.

## 4. Reproduction

Automated (real PostgreSQL container):

```bash
./mvnw --batch-mode test -Dtest=StockLockingTest,StockRaceConditionTest
```

Live (log in as root through the gateway and pass the token):

```bash
docker compose -f compose.yaml -f compose.labs.yaml up --build -d
```

```bash
labs/database/race-lab.sh <admin-token> 100 100
```

## 5. Failure modes and edge cases

- Optimistic retries must be bounded and every attempt must start a new transaction, otherwise the retry re-reads the same snapshot or loops for ever. Retrying inside one transaction under REPEATABLE READ would keep failing.
- Optimistic locking only protects writers that check the version. A writer that skips the check (the unsafe JPA save, a manual SQL update) breaks it. Here the atomic withdraw also adds 1 to the version so it cannot hide behind an optimistic reader.
- Pessimistic locks held across a slow step (an HTTP call, user think time) block every other request for that row. Keep the transaction short and set a lock timeout.
- Pessimistic locking on several rows can deadlock when two transactions lock in opposite order. That is the next unit.
- `lock_timeout` is a PostgreSQL setting. Another database needs another mechanism.

## 6. Interview questions

- When is optimistic locking the right choice and when does it fall apart?
- Why must each optimistic retry run in a new transaction?
- What does `SELECT ... FOR UPDATE` do to other readers of the same row in PostgreSQL?
- Why not just always use the single atomic UPDATE, and when can you not?
