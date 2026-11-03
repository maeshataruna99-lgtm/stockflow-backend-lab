# Guidebook 15: Race Condition and Lost Update (warehouse stock)

## 1. Concept

A race condition is a result that depends on how concurrent operations interleave. The classic form is a lost update: two transactions read the same value, each computes a new value from it and writes it back; the second write overwrites the first, so one change disappears.

Withdrawing stock as "read quantity, check it in Java, write quantity minus N" has this shape. PostgreSQL's default isolation level is READ COMMITTED: each statement sees the data committed before it started, and a plain SELECT takes no lock. Two transactions can therefore both read 100, both pass `quantity >= 1`, and both write 99. A `CHECK (quantity >= 0)` constraint does not help: 99 is a valid value, the lost update is invisible to it.

## 2. Implementation (warehouse-service)

- Table `stock_levels` (Flyway V3): `location_id` (foreign key), `product_id` (plain number, the product is in another database), `quantity` with `CHECK (quantity >= 0)`, unique per location and product.
- `POST /api/v1/stock/{id}/withdraw` (ADMIN): `StockService.withdraw` runs one statement, `UPDATE stock_levels SET quantity = quantity - :n WHERE id = :id AND quantity >= :n`. PostgreSQL locks the row for the UPDATE; a second withdrawal waits for the first to commit, then re-evaluates the `WHERE` against the committed value. 0 rows updated means the row is missing (404) or holds too little (409).
- `StockService.withdrawUnsafe` is the broken read-modify-write. It is reachable only through `POST /api/v1/stock/{id}/withdraw-unsafe`, which exists only when `stockflow.lab.unsafe-stock=true` (set by `compose.labs.yaml`, never by the core compose file; a test checks it is a 404 by default).
- Other stock endpoints: `POST /api/v1/stock` (create), `GET /api/v1/stock/{id}`. Not routed by the gateway; the labs call the warehouse directly.

## 3. Measured observation

Invariant checked: units accepted + units left = starting stock, and accepted never exceeds the starting stock. Concurrency comes from 16 threads in the tests and from 100 background `wget` processes in the lab; "accepted" means HTTP 200.

| Run | Endpoint | Stock | Requests | Accepted | Left | Accepted + left |
|---|---|---|---|---|---|---|
| Test (one round) | unsafe | 100 | 100 x 1 | 100 | 93 | 193 |
| Lab stack, run 1 | unsafe | 100 | 100 x 1 | 100 | 88 | 188 |
| Lab stack, run 2 | unsafe | 100 | 100 x 1 | 100 | 89 | 189 |
| Lab stack, run 3 | unsafe | 100 | 100 x 1 | 100 | 92 | 192 |
| Lab stack | unsafe | 50 | 100 x 1 | 100 | 35 | 135 |
| Lab stack, runs 1 to 3 | safe | 100 | 100 x 1 | 100 | 0 | 100 |
| Lab stack | safe | 50 | 100 x 1 | 50 (50 refused with 409) | 0 | 50 |
| Test | safe | 50 | 100 x 1 | 50 (50 refused) | 0 | 50 |
| Test | safe | 100 | 40 x 3 | 33 | 1 | 100 |

Reading the unsafe rows: every request was accepted and the quantity still stayed high, so about 90 of the 100 withdrawals were overwritten by other transactions. With a stock of 50 it accepted twice what existed.

Before the fix, the same two safe tests ran against the read-modify-write code and failed: stock 50 with 100 requests accepted 100; stock 100 with 40 requests of 3 gave accepted x 3 + left = 208.

One run per row (three for the lab with stock 100), one machine, not a benchmark. The exact numbers vary between runs; the invariant violation did not.

## 4. Reproduction

Automated (real PostgreSQL container):

```bash
./mvnw --batch-mode test -Dtest=StockRaceConditionTest,StockApiTest
```

Live (log in as root through the gateway and pass the token):

```bash
docker compose -f compose.yaml -f compose.labs.yaml up --build -d
```

```bash
labs/database/race-lab.sh <admin-token> 100 100
```

## 5. Failure modes and edge cases

- The unsafe test is probabilistic: it repeats up to 10 rounds until a lost update is seen. With 100 simultaneous requests the first round has shown it every time so far, but it is not a proof.
- The atomic UPDATE fixes one row. A rule that spans rows (move stock between two locations, reserve across several products) needs a transaction that takes locks in a fixed order, or a stronger isolation level (next units).
- The fix relies on the UPDATE taking the row lock. A `SELECT ... FOR UPDATE` before the Java check would also work (pessimistic locking), and a version column with a retry would too (optimistic locking). Both are the next unit.
- Retrying a refused withdrawal is safe only because a 409 changed nothing.

## 6. Interview questions

- Why does a `CHECK (quantity >= 0)` constraint not stop a lost update?
- What does READ COMMITTED guarantee, and what does it not?
- Why does the single UPDATE statement avoid the race while the Java check does not?
- Which fixes exist for a lost update, and what does each cost?
