# Guidebook 37: Eventual Consistency and CQRS

## 1. Concept

Command Query Responsibility Segregation (CQRS, topic 45) separates the write model (which enforces business invariants per row or aggregate) from the read model (which is shaped and pre-aggregated for fast queries). When the read model is updated asynchronously from domain events rather than inside the write transaction, the two models are **eventually consistent** (topic 42):
- During the **inconsistency window** between the write commit and the projector applying the event, a query against the read model returns stale data.
- "Eventually consistent" is only meaningful when paired with a **measurable convergence condition**: a concrete predicate that tells whether the read model has caught up to a given write (for example, `unpublished_outbox_rows == 0` and `read_model.last_outbox_id == max(stock_outbox.id)`), and that guarantees the read model equals the write projection once true.

## 2. Implementation

Lab in `warehouse-service` (`CqrsProjectionTest`, unit 9.5, against the real PostgreSQL container and `StockService` + `OutboxService`):
- **Command side**: `StockService.create` and `StockService.withdraw` update normalized `stock_levels` rows (one row per storage location and product) and insert `STOCK_CREATED` / `STOCK_WITHDRAWN` rows into `stock_outbox` in the same local transaction.
- **Query side**: table `cqrs_product_stock_view (product_id PRIMARY KEY, total_quantity, last_outbox_id)` holds the denormalized total stock for a product summed across all warehouse locations.
- **Projector**: relays pending events via `OutboxService.relayBatch(...)` and applies each event once via `OutboxService.consumeOnce(...)`, updating `total_quantity` and advancing `last_outbox_id = greatest(last_outbox_id, event.id)`.
- **Convergence predicate** (`isConverged(productId)`): returns `true` when `unpublished == 0` for the product's stock rows and `cqrs_product_stock_view.last_outbox_id` equals `max(stock_outbox.id)` for that product.

## 3. Measured observation

Automated test (`CqrsProjectionTest`, real PostgreSQL):
1. Two locations receive stock for one product (`10` and `15` units), and `4` units are withdrawn from the first location (`3` committed writes and `3` outbox events; write-model sum is `10 + 15 - 4 = 21`).
2. **Before the projector runs**:
   - Write-model sum (`SELECT sum(quantity) FROM stock_levels WHERE product_id = ?`): `21`
   - Read-model total (`cqrs_product_stock_view.total_quantity`): `0` (stale)
   - `isConverged(productId)`: `false` (`3` unpublished outbox events behind)
3. **After the projector runs**:
   - All `3` events are relayed and projected in `42,765 us` (run 1) and `30,420 us` (run 2).
   - `isConverged(productId)`: `true`
   - Read-model total: `21` (matches write-model sum)
4. **Replay**: passing the same `3` events to the projector a second time leaves `total_quantity` at `21` (`consumeOnce` skips already-processed `event_id`s).

## 4. Reproduction

In `apps/warehouse-service`:

```bash
./mvnw --batch-mode test -Dtest=CqrsProjectionTest
```

## 5. Failure modes and edge cases

- **Read-your-own-writes violation**: a client that writes to the command endpoint and immediately reads the CQRS view during the inconsistency window sees stale data unless the client passes the write's sequence/outbox ID and the query waits or falls back to the write model when `view.last_outbox_id < write_outbox_id` (same pattern as the WAL LSN check in guidebook 24).
- **Non-commutative projections**: an additive projection (`total_quantity = total_quantity + delta`) produces the right sum regardless of event order once all events arrive, whereas a "replace with latest snapshot" projection corrupts state if an older event is applied after a newer one; guarding updates with `WHERE last_outbox_id < EXCLUDED.last_outbox_id` prevents out-of-order regression.

## 6. Interview questions

- When does separating a read model (CQRS) pay for its extra complexity, and when is a SQL view or index on the write table enough?
- How do you measure and monitor the lag of an eventually consistent projection in production?
- How can an API give a user read-your-own-writes consistency when the read model is updated asynchronously?
