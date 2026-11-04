# Guidebook 38: Event Sourcing (Isolated Lab)

## 1. Concept

In traditional state-oriented persistence (used by `warehouse-service`'s `stock_levels` table), a database row stores only the current value (`quantity = 21`); an `UPDATE` overwrites the previous value and history is lost unless recorded separately.

In **Event Sourcing** (topic 46), the append-only log of immutable domain events is the source of truth:
- Every state change appends a new event with a monotonically increasing per-stream `version` (`STOCK_OPENED +20`, `UNITS_RECEIVED +10`, `UNITS_WITHDRAWN -7`).
- Current state is reconstructed by replaying the stream's events in version order; historical state at any prior version is reconstructed by replaying up to that version.
- Concurrent writers on the same stream are serialized without row locks on a state row by enforcing `UNIQUE (stream_id, version)`: two commands that both read version `4` and try to append version `5` collide on the unique index, and the loser fails with SQLSTATE `23505`.
- Because replaying thousands of events on every command gets slow, a periodic **snapshot** stores `(stream_id, version, state)` so replay starts from the latest snapshot and reads only events with `version > snapshot.version`.

## 2. Implementation

**Scope guardrail**: this is an isolated educational lab in the test tree of `warehouse-service` (`EventSourcedStockLedgerTest`, unit 9.6, real `postgres:16.15-alpine` container). It is not the primary persistence model of `warehouse-service`.

Tables in `EventSourcedStockLedgerTest`:
- `es_stock_events (seq PRIMARY KEY, stream_id, version, event_type, delta, reason, created_at, UNIQUE (stream_id, version))`
- `es_stock_snapshots (stream_id PRIMARY KEY, version, quantity)`

Operations:
- `appendEvent(streamId, expectedVersion, eventType, delta, reason)`: replays current state, checks the business invariant (`quantity + delta >= 0`) when the caller's `expectedVersion` matches, and inserts `version = expectedVersion + 1`.
- `replayFromStart(streamId, upToVersion)`: folds all events with `version <= upToVersion` and counts how many event rows were read.
- `saveSnapshot(streamId)` and `replayFromSnapshot(streamId)`: stores the folded state at the current version, then reconstructs state by loading the snapshot and folding only events with `version > snapshot.version`.

## 3. Measured observation

Automated test (`EventSourcedStockLedgerTest`, 3 tests, 5.185 s on real PostgreSQL):

| Experiment | Result |
|---|---|
| Append v1 `STOCK_OPENED (+20)`, v2 `UNITS_RECEIVED (+10)`, v3 `UNITS_WITHDRAWN (-7)`, v4 `UNITS_DAMAGED (-2)` | Replay at v1 = `20`, at v2 = `30`, at v3 = `23`, at v4 = `21`; overdraft `-25` at v4 throws `IllegalStateException` and leaves stream at v4 |
| Two callers both observe v4 and append v5 (`+5` vs `-3`) | First succeeds (v5, balance `26`); second fails with `DataIntegrityViolationException` (`SQLSTATE 23505` on `UNIQUE (stream_id, version)`) |
| Save snapshot at v5 (`quantity = 26`), then append v6 (`-4`), v7 (`+8`), v8 (`-1`) | Full replay from v1 reads `8` rows -> balance `29` (v8); replay from snapshot reads `3` rows (v6..v8) -> balance `29` (v8) |

## 4. Reproduction

In `apps/warehouse-service`:

```bash
./mvnw --batch-mode test -Dtest=EventSourcedStockLedgerTest
```

## 5. Failure modes and edge cases

- **Schema evolution (upcasting)**: because events are immutable, changing an event's schema later requires either backward-compatible fields or an upcaster that transforms old event versions during replay.
- **Querying across streams**: an event store is indexed by `(stream_id, version)`, so answering a cross-stream query ("all products with stock below 5") requires a separate CQRS projection (guidebook 37).
- **External side effects during replay**: replaying events to rebuild state must never re-trigger external side effects (emails, payments, webhooks); side effects belong to command handlers or outbox consumers, never to the state fold function.

## 6. Interview questions

- How does optimistic concurrency work in an event-sourced stream without a `SELECT ... FOR UPDATE` on a state row?
- Why do event-sourced systems almost always need CQRS read projections for queries?
- How do snapshots reduce replay cost, and what happens if a snapshot is lost or corrupted?
- What is event upcasting, and why can you not simply run an `ALTER TABLE` to rewrite old domain events?
