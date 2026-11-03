# Guidebook 22: Foreign Key and Unique Constraints

## 1. Concept

Constraints are rules the database itself enforces, on every write from every client, inside the transaction:
- `UNIQUE` / primary key: no two rows share the value. Violation: SQLSTATE `23505`.
- `FOREIGN KEY`: a child row must point at an existing parent row, and a parent row cannot be deleted while children point at it. Violation: `23503`.
- `CHECK` / `NOT NULL`: a row must satisfy a condition. Violations: `23514`, `23502`.

An application-level pre-check ("is this code already taken?", "does this location exist?") is not a substitute. Between the check and the write another transaction can change the data (check-then-act race), so the pre-check only produces a friendly answer in the common case. The constraint is the guard that holds under concurrency; the application must then report its violation correctly.

## 2. Implementation

Existing constraints used in this unit (warehouse-service): `UNIQUE (code)` on `warehouses`, `UNIQUE (location_id, product_id)` and `FOREIGN KEY (location_id)` on `stock_levels`, `FOREIGN KEY (warehouse_id)` on `storage_locations`.

The code already did both: `existsByCode` pre-check, then `save`, with `GlobalExceptionHandler` answering a `DataIntegrityViolationException` as 409. The gap was the answer: every violation was reported as "The request violates a unique constraint" (title "Duplicate resource"), also a foreign key or check violation. The handler (warehouse-service and catalog-service had the same one) now reads the SQLSTATE of the root cause:

| SQLSTATE | Status | Title | Detail |
|---|---|---|---|
| 23505 unique | 409 | Duplicate resource | The request violates a unique constraint |
| 23503 foreign key | 409 | Reference conflict | The request refers to a row that does not exist or is still referenced by other rows |
| anything else | 409 | Constraint violation | The request violates a data constraint |

The status stays 409 for all three; a violation that is not unique or foreign key (check, not null) means a request got past validation and is reported generically, not as a duplicate.

## 3. Measured observation

`ConstraintTest` (warehouse-service, real PostgreSQL container) and the lab script `labs/database/constraint-lab.sh`:

| Scenario | Result |
|---|---|
| 12 simultaneous `POST /warehouses` with one code (MockMvc) | 1 x 201, 11 x 409, no 500, one row. 9 of the 11 refusals came from the unique constraint (the pre-check had already passed for them), 2 from the `existsByCode` pre-check |
| Check-then-insert with 8 threads that all check, then all insert, on a table without a constraint | 8 rows inserted for one sku |
| The same on a table with `UNIQUE` | 1 row inserted, 7 refused with `23505`, 1 row |
| Delete a location that has stock | `23503`, the row stays |
| An uncommitted insert of a stock row, then a delete of its location from another connection | the delete does not finish (500 ms later still running, 1 backend in `pg_stat_activity` with `wait_event_type = 'Lock'` for that statement); after the commit the delete fails with `23503`, the stock row exists |
| Live, 20 simultaneous creates of one warehouse code straight at the warehouse container, 3 runs | 1 x 201, 19 x 409, 0 other, 1 row with that code, each run |
| Handler with SQLSTATE 23503 before the fix | title "Duplicate resource" (the test failed: `expected "Reference conflict" but was "Duplicate resource"`) |
| Handler after the fix, 23505 / 23503 / 23514 | Duplicate resource / Reference conflict / Constraint violation |

The first row shows why the constraint matters: in that run 9 of 12 requests passed the pre-check at the same time. One run for the 12-request test, one for the thread experiment; three runs live; one machine.

## 4. Reproduction

Automated:

```bash
./mvnw --batch-mode test -Dtest=ConstraintTest
```

(in `apps/warehouse-service`), and `ConstraintErrorMappingTest` in `apps/catalog-service`.

Live (log in as root through the gateway and pass the token):

```bash
docker compose -f compose.yaml -f compose.labs.yaml up --build -d
```

```bash
labs/database/constraint-lab.sh <admin-token> 20
```

The lab leaves one warehouse per run in the compose database (`LAB-<timestamp>`).

## 5. Failure modes and edge cases

- A unique violation inside a longer transaction aborts the whole transaction in PostgreSQL: later statements fail with "current transaction is aborted" until rollback. Catching the exception inside the transaction and carrying on does not work without a savepoint.
- The loser of a unique race gets its error when its insert runs (or at commit with deferred constraints), after it may have done other work in the same transaction; that work is rolled back with it.
- A foreign key blocks deletes of the parent and makes a delete wait for children being inserted; long transactions that insert children hold up parent deletes.
- `ON DELETE CASCADE` would remove the children instead of refusing; it hides the reference conflict and is a deliberate design choice, not used here.
- Constraints do not cover rules across several rows (guidebook 18, write skew).
- Mapping by SQLSTATE depends on the root cause being a PostgreSQL error with a state; wrapping layers that hide it would fall into the generic answer.

## 6. Interview questions

- Why is `if (!exists(x)) insert(x)` not enough to keep x unique?
- What is the difference between SQLSTATE 23505 and 23503, and what should an API answer for each?
- Why does a delete of a parent row wait while another transaction inserts a child?
- What happens to a PostgreSQL transaction after a constraint violation?
