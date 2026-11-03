# Guidebook 17: Deadlock (warehouse stock transfer)

## 1. Concept

A deadlock is a cycle of waiting: transaction 1 holds a lock that transaction 2 needs, and transaction 2 holds a lock that transaction 1 needs. Neither can continue and neither will ever release, because releasing happens at commit.

Moving stock between two rows needs two row locks. If transfer A to B locks A then B, and a concurrent transfer B to A locks B then A, each can take its first lock and then wait for the other's. PostgreSQL does not let this hang: after `deadlock_timeout` (1 s by default) a waiting transaction runs the deadlock check, finds the cycle, aborts one transaction with `ERROR: deadlock detected` (SQLSTATE `40P01`) and rolls it back, which frees its locks, and the other transaction completes.

Two ways to deal with it:
- Prevent it: take locks in one global order (for example ascending id) in every code path that needs more than one. A cycle needs opposite orders, so it cannot form.
- Recover from it: treat `40P01` as a retryable failure, because the aborted transaction changed nothing. A bounded retry is the safety net, not the design.

## 2. Implementation (warehouse-service)

- `StockService.transfer(fromId, toId, quantity)` (`POST /api/v1/stock/transfer`, ADMIN): one transaction, both rows locked in one statement `SELECT ... WHERE id IN (...) ORDER BY id FOR NO KEY UPDATE`, so the locks are taken in ascending id order whatever the direction. It then checks the source quantity (409 when short, 404 when a row is missing) and moves the units with two `UPDATE ... SET quantity = quantity + :delta, version = version + 1`. `lock_timeout` (2 s, `stockflow.stock.lock-timeout`) is set for the transaction, so a lock wait is bounded in any case.
- `StockService.transferUnordered(from, to, quantity, pauseMillis)` is the lab version: it locks `from`, sleeps `pauseMillis` with `pg_sleep` so that the overlap is certain, then locks `to`. Endpoint `POST /api/v1/stock/transfer-unordered?pauseMillis=`, present only when `stockflow.lab.unsafe-stock=true`.
- Error mapping: a `PessimisticLockingFailureException` whose root cause has SQLSTATE `40P01` is a `409` problem with title "Deadlock" (nothing changed, retry); any other lock failure (the lock timeout) stays `503` "Row locked".
- Hibernate takes `FOR NO KEY UPDATE` for `PESSIMISTIC_WRITE` on PostgreSQL, which still conflicts with another `FOR NO KEY UPDATE` and with `UPDATE`, so the deadlock is the same.

## 3. Measured observation

| Run | Endpoint | Result |
|---|---|---|
| Test (2 opposite transfers, 400 ms pause) | unordered | exactly one failed with SQLSTATE 40P01, resolved in 1541 ms, the other committed; after the loser's rollback the two rows differed from the start by only the winner's single unit; retrying the loser's transfer with `transfer` brought both rows back to 10 and 10 |
| Test (40 transfers, 16 threads, both directions, 100 units per row) | ordered | 40 of 40 succeeded, both rows back at 100 |
| Lab stack, round 1 | unordered | statuses 200 and 409, rows 9 and 11, PostgreSQL logged 1 "deadlock detected" |
| Lab stack, round 2 | unordered | 200 and 409, rows 11 and 9, +1 deadlock logged |
| Lab stack, round 3 | unordered | 200 and 409, rows 9 and 11, +1 deadlock logged |
| Lab stack, rounds 1 to 3 | ordered | 200 and 200 each round, rows 10 and 10, +0 deadlocks logged |

In every unordered round, one transfer committed and the other was rolled back with no partial change. The loser was a different one in different rounds. One run per row (three rounds in the lab), one machine, not a benchmark.

## 4. Reproduction

Automated (real PostgreSQL container):

```bash
./mvnw --batch-mode test -Dtest=StockDeadlockTest
```

Live (log in as root through the gateway and pass the token):

```bash
docker compose -f compose.yaml -f compose.labs.yaml up --build -d
```

```bash
labs/database/deadlock-lab.sh <admin-token> 3
```

To read the database's own record of the deadlock:

```bash
docker compose logs postgres | grep -A4 "deadlock detected"
```

## 5. Failure modes and edge cases

- The 1 s `deadlock_timeout` is the delay before PostgreSQL even checks for a cycle, so a deadlock costs at least a second of waiting for both transactions. Lowering it makes checks more frequent and costs CPU.
- Ordering by id only works if every path that locks several rows uses it. One path that locks in another order, or locks the rows with separate statements in input order, brings the deadlock back.
- Locks are also taken by foreign key checks and by `UPDATE` on parent rows; ordering application locks does not cover every source of cycles.
- Retrying a `40P01` is safe only because the whole transaction is rolled back; work with outside effects (an HTTP call, a message) inside the transaction is not undone.
- Locking rows in one `SELECT ... ORDER BY id FOR UPDATE` relies on PostgreSQL locking rows in the order the sorted result is produced. The test with 16 threads in both directions did not deadlock, but this is a property of the execution plan, so the order should be rechecked when the query changes.

## 6. Interview questions

- What exactly makes a set of transactions deadlocked, and why does waiting longer not help?
- How does PostgreSQL find a deadlock, and what happens to the transaction it picks?
- Why does a global lock order prevent deadlocks, and what breaks it?
- When is it right to retry on SQLSTATE 40P01, and when not?
