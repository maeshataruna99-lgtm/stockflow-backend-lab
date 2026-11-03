# Guidebook 24: Read Replicas and Replication Lag (PostgreSQL streaming replication)

## 1. Concept

A read replica is a second PostgreSQL server that continuously receives the primary's write-ahead log (WAL) and replays it, so it holds a copy of the data and can answer read-only queries. It takes read load off the primary and survives as a standby. It does not make writes faster, and it is not instantly up to date.

- Streaming replication: the replica's WAL receiver connects to the primary (a role with `REPLICATION`, a `replication` entry in `pg_hba.conf`) and the primary's WAL sender streams WAL records as they are written.
- Asynchronous by default (`sync_state = async`): the primary commits without waiting for the replica. A committed write can therefore be missing on the replica for a short time (replication lag), and a primary crash can lose writes the replica has not received yet.
- Hot standby: the replica accepts read-only connections. Any write fails with SQLSTATE `25006` (read-only transaction).
- Replay can fall behind (a slow replica, a long query conflicting with replay, a network pause). While it does, reads from the replica return old data (stale reads).

Consequence for an application: a read after your own write may not see it if it goes to a replica (read-your-writes violated). Ways to handle it: read your own recent writes from the primary; or make the read wait until the replica has replayed up to the LSN (WAL position) of the write and otherwise fall back to the primary; or accept staleness for data where it does not matter.

## 2. Implementation (lab infrastructure, no change to the services)

The services still use the single `postgres`. The replica lab is separate infrastructure so the experiments cannot touch the service databases:
- `compose.labs.yaml`, profile `replica`: `pg-primary` (postgres:16.15-alpine, init script `labs/database/replica/01-replication.sh` creates the role `replicator` and the `pg_hba` entry) and `pg-replica` (entrypoint `labs/database/replica/replica-entrypoint.sh`: waits for the primary, `pg_basebackup -R -X stream`, then starts as a hot standby with `application_name=lab_replica`). No host ports. The lab roles reuse `POSTGRES_SUPER_PASSWORD` (throwaway data on the internal network).
- `.gitattributes` forces LF for `*.sh`, because these scripts are mounted into Linux containers and a CRLF checkout breaks them.
- `ReplicationLagTest` (warehouse-service) starts the same two scripts in two Testcontainers containers on one network.
- `labs/database/replica-lab.sh` runs the same steps against the compose profile.

Not done: routing the services' reads to a replica (a second `DataSource`, `@Transactional(readOnly = true)` routing). The unit shows the database behavior the application would have to handle.

## 3. Measured observation

3.1 The replica is a standby and is read-only (test and compose, same result): `pg_is_in_recovery()` is `true` on the replica and `false` on the primary; `pg_stat_replication` on the primary lists `lab_replica`, `state = streaming`, `sync_state = async`; an `INSERT` on the replica fails with `cannot execute INSERT in a read-only transaction` (SQLSTATE 25006) and the row does not exist on the primary.

3.2 Time until a committed row is visible on the replica:

| Measurement | Result |
|---|---|
| Test, a new connection for every poll, 5 writes | 49, 56, 40, 41, 41 ms |
| Test, one persistent connection per database, 5 writes | 65, 21, 28, 111, 44 ms |
| Compose lab, polling over the Docker network from inside the primary container, run 1 | 104, 105, 102, 100, 105 ms |
| Compose lab, run 2 | 289, 210, 191, 225, 91 ms |

How to read these: the polling itself has a resolution. Each poll in the compose lab opens a new connection with `psql`, which costs about 90 to 100 ms, so the 91 to 105 ms values mean "visible at the first poll"; the replication delay is below what that method can resolve. The persistent-connection test resolves finer and shows 21 to 111 ms for the whole path (the primary commit, WAL send, replay, and the poll). Two earlier versions of these measurements were wrong and are not used: polling with a `docker exec` per check measured 1.3 to 1.6 s of `docker exec` start-up (and about 1 s with a single exec per check), not replication. The numbers are for an idle replica on the same machine; they say nothing about a network between hosts or a busy replica.

3.3 A replica that is behind (`pg_wal_replay_pause()` on the replica: it keeps receiving WAL but stops applying it), then a write on the primary:

| Observation | Result (test and compose, same in both) |
|---|---|
| Rows with the new marker | primary 1, replica 0 (a stale read) |
| Replay lag seen from the primary | 192 bytes (`pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn)`) |
| Has the replica reached the LSN of the write? | no (`pg_last_wal_replay_lsn() >= lsn` is false; the test waited 500 ms to be sure) |
| After `pg_wal_replay_resume()` | the replica reached the LSN: 25 ms and 2 ms in the two test runs, 246 and 230 ms in the compose lab (that figure includes the resume call and the 100 ms polling resolution); the replica then returns 1 |

The LSN check is the read-your-writes guard: take `pg_current_wal_lsn()` on the primary after the write, ask the replica whether its replay position is at or past it, and read from the primary if it is not.

One run per cell in the tables unless two are shown; one machine, async replication, an idle database; not a benchmark.

## 4. Reproduction

Automated (starts a primary and a replica container, about 30 s):

```bash
./mvnw --batch-mode test -Dtest=ReplicationLagTest
```

(in `apps/warehouse-service`). Compose:

```bash
docker compose -f compose.yaml -f compose.labs.yaml --profile replica up -d
```

```bash
labs/database/replica-lab.sh
```

Stop the lab:

```bash
docker compose -f compose.yaml -f compose.labs.yaml --profile replica rm -sf pg-replica pg-primary
```

## 5. Failure modes and edge cases

- Async replication can lose the most recent commits on failover; synchronous replication (`synchronous_standby_names`) removes that and makes every commit wait for the replica. Not tested here.
- A long read query on the replica can conflict with replay of changes it still needs; PostgreSQL then either delays replay (`max_standby_streaming_delay`) or cancels the query. Not tested here.
- A replica that falls too far behind needs WAL the primary has already removed (`wal_keep_size`, replication slots) and must be rebuilt.
- Monotonic reads: two reads from two different replicas can go backwards in time. Pin a session to one replica or to the primary.
- Writes sent to the replica fail with 25006: routing must send every write, and every read that must see a write, to the primary.
- Replica lag metrics in the primary's `pg_stat_replication` (`write_lag`, `flush_lag`, `replay_lag`) are only updated while there is traffic.

## 6. Interview questions

- Why can a read after a write return the old value when it goes to a replica?
- What does asynchronous replication risk on a primary failure?
- How can an application keep read-your-writes with replicas?
- What does `pg_wal_replay_pause()` simulate in production?
- Why is a read replica not a fix for write throughput?
