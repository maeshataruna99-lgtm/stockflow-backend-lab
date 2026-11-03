# Guidebook 25: Partitioning and Sharding (isolated experiments)

Status of this unit: two isolated experiments in the test code. Neither the services' schema nor the compose stack uses partitioning or sharding; the StockFlow architecture is one PostgreSQL per service. Nothing here should be read as "StockFlow is partitioned" or "StockFlow is sharded".

## 1. Concept

Both split a large dataset into smaller pieces. They differ in where the pieces live and who knows about them.

- Partitioning (declarative, inside one PostgreSQL server): one logical table, stored as several physical tables (partitions) by a rule on a column: range (months), hash (spread evenly), list. The database routes inserts and, when a query filters on the partition key, skips the partitions that cannot match (partition pruning). Queries and transactions still see one table and one database.
- Sharding (across servers): the data is split over several independent database servers by a shard key. The application (or a proxy) decides which server holds a row. Each shard is a complete database of its own, so what the database guaranteed for one table (unique keys, joins, transactions, foreign keys) holds only inside one shard.

Partitioning buys maintenance (drop a month instead of deleting it) and smaller indexes and scans per query. Sharding buys capacity beyond one server (storage, write throughput). Replication (guidebook 24) copies the same data to read from; it does neither.

## 2. Implementation (tests only)

- `PartitioningTest` (warehouse-service test tree): its own PostgreSQL container; `lab_events` with 1,200,000 rows range-partitioned by month on `created_at` (12 partitions and a default partition), an unpartitioned twin `lab_events_flat` with the same rows and an index on `created_at`, a hash-partitioned `lab_hash` (4 partitions), and small tables for constraint behavior.
- `ShardingTest`: two PostgreSQL containers are two shards of an `accounts` table; the router in the test is `CRC32(id) mod 2`; a counter records how many shards each operation touches; resharding is computed in memory for modulo and for a consistent-hash ring (128 points per shard).

## 3. Measured observation

3.1 Partitioning (PostgreSQL 16.15, two runs of the class where two values are shown):

| Experiment | Result |
|---|---|
| Rows per monthly partition | 92,064 to 101,928 (the load puts 1,200,000 rows over 365 days) |
| Filter on one day (partition key) | the plan reads 1 partition (`lab_events_2026_03`); the flat table reads `lab_events_flat` through its index |
| Time of that query, partitioned / flat | 0.86 ms / 2.48 ms in run 1, 3.02 ms / 3.10 ms in run 2: no stable speed difference |
| Filter on other columns (`user_id`, `kind`) | the plan reads 13 tables (12 partitions and the default one); 70.6 ms partitioned / 90.3 ms flat in run 1, 82.0 ms / 78.7 ms in run 2: no difference |
| Remove January (101,927 rows) | `DELETE` 138 ms and 137 ms; `DETACH PARTITION` + `DROP TABLE` 19 ms and 21 ms |
| `ALTER TABLE ... ADD PRIMARY KEY (id)` on the partitioned table | refused, SQLSTATE `0A000`; `PRIMARY KEY (id, created_at)` works |
| Insert outside every range | without a default partition: `23514`; with one: the row lands in `lab_events_default` |
| 100,000 user ids into 4 hash partitions | 24,925, 24,971, 24,978, 25,126 rows |

What it supports: partitioning gives pruning (fewer tables read) and cheap retention (drop instead of delete: about 6.5 to 7 times faster here, and it leaves no dead rows to vacuum). It did not make the queries measurably faster on this data set, where the flat table already had an index on the same column. A filter that misses the partition key reads every partition.

3.2 Sharding (two shards, one run per row; the 2-to-3 resharding numbers are deterministic):

| Experiment | Result |
|---|---|
| 10,000 accounts hashed over 2 shards | 5,001 and 4,999 (5,003 and 4,999 in an earlier run that also held two extra rows from other tests) |
| Lookup by id (the shard key) | 1 shard asked |
| Lookup by email | 2 shards asked (scatter-gather); the application merges the results |
| `UNIQUE (email)` | holds inside a shard (a second insert on the same shard fails with `23505`); the same email inserted on the other shard succeeds: it existed 2 times across the shards |
| Transfer of 30 between accounts on different shards, process dies between the two commits | balances add up to 170 instead of 200: 30 units vanished. A compensating credit restores 200 |
| Transfer of 30 inside one shard, failure after the debit, one transaction | rolled back, total stays 200 |
| Resharding 2 to 3 shards, 100,000 keys | modulo moves 66.7 % of the keys; a hash ring with 128 points per shard moves 30.4 % |

What it supports: sharding spreads rows evenly, but each guarantee that crossed shards in the single database is gone: global uniqueness, atomic transfers, joins, queries without the shard key. Moving to more shards with modulo relocates most of the data; a ring relocates about one in three keys when going from 2 to 3 shards.

Measurements: one machine, containers, not a benchmark; the timings are of small queries and have visible run-to-run noise (see the one-day query).

## 4. Reproduction

In `apps/warehouse-service` (about one minute each):

```bash
./mvnw --batch-mode test -Dtest=PartitioningTest
```

```bash
./mvnw --batch-mode test -Dtest=ShardingTest
```

## 5. Failure modes and edge cases

- Partition key choice: a key that queries rarely filter on gives no pruning; a key with skew (one huge partition) gives little. Primary and unique keys must contain the partition key, so a globally unique id on a partitioned table needs another mechanism.
- Partitions need maintenance: creating next month's partition before it is needed (otherwise rows go to the default partition or fail), detaching and dropping old ones. Too many partitions slow planning.
- A shard key must be in almost every query and every row; a hot key puts the load on one shard. Re-sharding or a lookup table changes both the code and the data.
- Without distributed transactions, a cross-shard write needs a saga, an outbox or a reconciliation job (Phase 9). A compensating action can itself fail.
- Cross-shard reads are inconsistent unless the shards are read at one point in time; the lab does not address this.
- Rebalancing while serving traffic (copy, dual-write, switch the router) is the hard part and is not exercised here.

## 6. Interview questions

- What is the difference between partitioning, sharding and replication?
- Why must a primary key on a partitioned table include the partition key?
- Why is dropping a partition so much cheaper than deleting its rows?
- Which database guarantees disappear when a table is sharded, and what replaces them?
- Why does modulo hashing make adding a shard expensive, and what does consistent hashing change?
