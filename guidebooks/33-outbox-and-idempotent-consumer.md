# Guidebook 33: Transactional Outbox, Background Workers and Idempotent Consumer

## 1. Concept

In an event-driven architecture (topic 39), a service that changes its own database and also publishes a message to a broker faces the dual-write problem: the database and the broker are two separate systems with no shared transaction.
- If the service publishes to the broker before the database commit and the commit then fails, downstream consumers act on a write that never happened (a phantom event).
- If the service commits to the database first and crashes before publishing to the broker, the state changed without an event and downstream projections drift out of sync.

The transactional outbox pattern (topic 44) turns the dual write into one local database transaction:
1. Inside the same database transaction that updates the business row, the service inserts an event row into an outbox table (`stock_outbox`). Both rows commit together or roll back together.
2. A scheduled background worker (topics 37 and 38) polls `stock_outbox` for unpublished rows (`published_at IS NULL`), claims a batch with `FOR UPDATE SKIP LOCKED` so concurrent worker instances do not duplicate work, publishes each event to the broker, and sets `published_at = now()`.
3. Because publishing to the broker and updating `published_at` are still two steps, a crash between them causes the next worker poll to publish the same `event_id` a second time. The outbox therefore guarantees **at-least-once** delivery, never "exactly once" by itself.
4. Every consumer pairs the outbox with an idempotent inbox (`processed_events`): inside the same transaction as its side effect, the consumer runs `INSERT INTO processed_events (event_id, event_type) VALUES (?, ?) ON CONFLICT (event_id) DO NOTHING`. If `1` row is inserted, the side effect runs; if `0` rows are inserted, the duplicate delivery is ignored.

## 2. Implementation

Production code in `warehouse-service` (unit 9.1):
- Flyway migration `V8__stock_outbox.sql`:
  - `stock_outbox (id, event_id UNIQUE, aggregate_id, event_type, payload, created_at, published_at)` with a partial index `WHERE published_at IS NULL`.
  - `processed_events (event_id PRIMARY KEY, event_type, processed_at)`.
- `StockService.create`, `StockService.withdraw`, `StockService.applyDelta`, and `StockService.transfer` call `OutboxService.record(...)` (`Propagation.MANDATORY`) inside their `@Transactional` methods, recording `STOCK_CREATED`, `STOCK_WITHDRAWN`, `STOCK_REPLENISHED`, and `STOCK_TRANSFERRED`.
- `OutboxService.relayBatch(batchSize, publisher)` selects up to `batchSize` unpublished rows ordered by `id` with `FOR UPDATE SKIP LOCKED`, invokes the publisher, and updates `published_at = now()`.
- `OutboxService.consumeOnce(eventId, eventType, sideEffect)` claims `eventId` in `processed_events` with `ON CONFLICT DO NOTHING` and runs `sideEffect` in the same local transaction.
- `OutboxService.purgePublished()` (`@Scheduled` every hour) deletes published rows older than `stockflow.stock.idempotency-retention` (default 24 hours).

## 3. Measured observation

Tests (`OutboxAndWorkerTest`, real PostgreSQL 16.15 via Testcontainers):

| Scenario | Result |
|---|---|
| `create(10)` followed by `withdraw(4)` | 2 rows committed in `stock_outbox` (`STOCK_CREATED`, `STOCK_WITHDRAWN`), `unpublishedCount() == 2` |
| `withdraw(9)` from a stock of 5 (throws `InsufficientStockException`) | transaction rolls back; stock stays at 5 and no `STOCK_WITHDRAWN` row exists in `stock_outbox` |
| 12 events queued, 4 concurrent worker threads calling `relayBatch(3, ...)` | all 12 distinct `event_id`s published once across the 4 workers (`FOR UPDATE SKIP LOCKED`); `unpublishedCount() == 0` |
| Relay crashes after delivering to broker before committing `published_at`, then polls again | broker receives the same `event_id` twice; `consumeOnce` returns `true` on the first delivery and `false` on the second; side effect counter is `1` |
| `purgePublishedOlderThan(24h)` with one 2-day-old published row, one 1-hour-old published row, and one unpublished row | deletes `1` row (the 2-day-old published row); the recent published row and the unpublished row remain |

Total execution time for `OutboxAndWorkerTest` (5 tests): 0.841 s on the shared PostgreSQL container.

## 4. Reproduction

In `apps/warehouse-service`:

```bash
./mvnw --batch-mode test -Dtest=OutboxAndWorkerTest
```

## 5. Failure modes and edge cases

- A consumer that records `event_id` in `processed_events` in a separate transaction from its business update reintroduces the dual-write bug on the consumer side; both writes must share one transaction, or the business write itself must be naturally idempotent.
- `FOR UPDATE SKIP LOCKED` prevents workers from blocking each other, but it can reorder events across workers when batch size > 1 and one worker finishes its transaction before an earlier worker. Consumers that depend on per-aggregate order must either partition by `aggregate_id` or check version numbers in the payload.
- Unbounded retention of published rows bloats `stock_outbox`; the partial index `WHERE published_at IS NULL` keeps relay polls fast, and the hourly purge removes old published rows.
- If a poison event makes `publisher.publish(event)` throw every time, the batch transaction rolls back and the row stays `published_at IS NULL`; in production an attempt counter on the outbox row is needed so a broken row does not stall a batch.

## 6. Interview questions

- Why can a service not just publish to RabbitMQ or Kafka at the end of a `@Transactional` method?
- How does `FOR UPDATE SKIP LOCKED` allow multiple background workers to poll the same outbox table safely?
- Why does the transactional outbox provide at-least-once delivery rather than exactly-once delivery?
- How does `INSERT ... ON CONFLICT DO NOTHING` in `processed_events` make a consumer idempotent?
