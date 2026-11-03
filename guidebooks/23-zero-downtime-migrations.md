# Guidebook 23: Zero-Downtime Migrations (expand, migrate, contract)

## 1. Concept

A deploy replaces instances one at a time, so for a while the old and the new release run against the same database. A schema change that only the new release understands (a rename, a dropped column) breaks the old one in that window. Zero-downtime migration means every schema step is compatible with the release before it and the release after it.

Expand / migrate / contract splits a breaking change (here: rename `warehouses.address` to `street_address`) into steps that are each safe:
1. Expand: add the new column and keep it in step with the old one (a trigger, or dual writes in the application). Backfill existing rows. Nothing the old release uses is removed. Deploy the migration; the old release keeps running.
2. Migrate: deploy the release that reads and writes the new column. Both columns are kept equal, so a mix of old and new instances, and a rollback, are safe.
3. Contract: once no old instance is running, drop the old column and the trigger.

The migration itself is also a risk for a live system, apart from compatibility: DDL needs locks (guidebook 17), and a statement waiting for a lock holds up everything behind it.

## 2. Implementation (warehouse-service)

- `V5__warehouse_street_address_expand.sql`: `ADD COLUMN street_address`, the trigger function `warehouses_sync_address` (a change of either column is copied to the other; when both change, the new column wins; on insert, whichever is missing is filled), the trigger, and a backfill `UPDATE`.
- Release switch: `Warehouse.address` is mapped to the column `street_address` (`@Column(name = "street_address")`). The API field stays `address`, so clients see no change.
- `V6__warehouse_street_address_contract.sql`: drops the trigger, the function and the column `address`.
- In this single-environment lab V5, the code switch and V6 are applied by one deploy. In a real rollout V6 ships at least one release later, after the last instance that uses `address` is gone. `ZeroDowntimeMigrationTest` runs the real migrations in the staged order (Flyway `target`) to prove each step.

Rules this unit follows for migration SQL:
- Add, do not rename or drop, until the contract step.
- A new column starts nullable or with a constant default (a catalog change, no table rewrite in PostgreSQL 11+).
- Backfill in batches on a large table, committing between batches. Here the table has a handful of rows, so one statement.
- Index builds on a live table: `CREATE INDEX CONCURRENTLY` (section 3.3).
- Set `lock_timeout` for DDL so it gives up instead of queueing (section 3.2).

## 3. Measured observation

3.1 Compatibility at each step (`ZeroDowntimeMigrationTest`, PostgreSQL 16.15 container, real migrations, "old app" and "new app" being the SQL each release sends):

| Step | Old release (column `address`) | New release (column `street_address`) |
|---|---|---|
| Before (V4) | works | fails: column does not exist (42703) |
| After expand (V5) | works; an insert, an update and an update to NULL show up in `street_address`; the seeded warehouse and the earlier row were backfilled with no difference between the columns | works; an insert and an update show up in `address`; a statement that sets both columns differently ends with the new value in both |
| After contract (V6) | fails: column does not exist (42703) | works; data of the seeded warehouse and of earlier rows intact; the trigger is gone |

The "fails after contract" row is why the contract step waits for the last old instance.

3.2 A migration waiting for a lock stalls the table (`lab_lock` table; T1 holds an open transaction that read it for about 3 s; an `ALTER TABLE ... ADD COLUMN` is started; 200 ms later a plain `SELECT` is started). Two runs of the class (run 1 / run 2):

| Migration setting | pg_stat_activity during the wait | Later `SELECT` waited | ALTER result |
|---|---|---|---|
| no `lock_timeout` | 2 backends waiting on a lock (the ALTER and the SELECT) | 2862 / 2858 ms | migrated after T1 committed |
| `lock_timeout = 500` | (the ALTER had already given up when sampled) | 323 / 324 ms | failed with 55P03, nothing changed |

The SELECT was not blocked by T1 (a read does not conflict with a read) but by the ALTER queued in front of it. With a timeout, the stall is bounded by the timeout, and the migration is retried.

3.3 Index build while writing (`lab_big`, 1,000,000 rows, one writer inserting at most about 200 rows per second; slowest single insert during the build). Run 1 / run 2:

| Statement | Build time | Slowest insert |
|---|---|---|
| `CREATE INDEX` | 1738 / 1106 ms | 1717 / 1079 ms (blocked for the build) |
| `CREATE INDEX CONCURRENTLY` | 2323 / 1452 ms | 333 / 164 ms |

`CONCURRENTLY` takes longer to build and still delays a write briefly at its phase boundaries, but writers are not blocked for the build. It cannot run inside a transaction (SQLSTATE 25001, tested).

3.4 Flyway and `CONCURRENTLY`: a migration with `executeInTransaction=false` (script config file `V100__concurrent_index.sql.conf`) built a valid index (`pg_index.indisvalid` true) only after setting `flyway.postgresql.transactional.lock=false`. With Flyway's default (a transactional advisory lock) the run did not finish in 8 minutes: `pg_stat_activity` showed the index build waiting on a `virtualxid` while Flyway's other connection sat "idle in transaction". I stopped the run and the container by hand; the first attempt is not repeated in the test.

One run per cell where not stated; two runs of the class where two values are shown; one machine; not a benchmark.

## 4. Reproduction

Automated (own PostgreSQL container, about 30 s):

```bash
./mvnw --batch-mode test -Dtest=ZeroDowntimeMigrationTest
```

(in `apps/warehouse-service`). Live: rebuild the warehouse (`docker compose -f compose.yaml -f compose.labs.yaml up --build -d warehouse`); Flyway applies V5 and V6 at start.

## 5. Failure modes and edge cases

- The sync trigger adds a little cost to every write on the table while it exists, and it is code that must be removed in the contract step. Dual writes in the application are the alternative; they need both old and new code paths to write both columns.
- A rollback to the old release is safe until the contract step. After V6 it is not: the old release's SQL fails. Keep the contract step for a later deploy and make it irreversible only when the old release is gone.
- A long transaction or an idle-in-transaction session blocks DDL and, through the lock queue, the table. Set `lock_timeout` and `statement_timeout` for migrations and retry.
- Backfill of a big table in one `UPDATE` holds locks and creates a lot of dead rows. Do it in batches by primary key range.
- A `NOT NULL` constraint added to a populated column scans the table under a strong lock. The safe order is: add a `CHECK (col IS NOT NULL) NOT VALID`, `VALIDATE CONSTRAINT`, then set `NOT NULL` (not exercised here).
- Replacing the column type, splitting a table or changing the meaning of a value follow the same three steps but need application changes in the migrate step.

## 6. Interview questions

- Why can a column rename not be one migration in a rolling deploy?
- What does each of the three steps guarantee about the old and the new release?
- Why can an `ALTER TABLE` that is only waiting block ordinary `SELECT`s?
- Why does `CREATE INDEX CONCURRENTLY` not work inside a Flyway migration by default?
- When is it safe to run the contract step?
