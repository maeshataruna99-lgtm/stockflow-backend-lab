# Guidebook 36: Distributed Transactions and the Saga Pattern

## 1. Concept

When a business operation spans multiple services or independent databases (for example, reserving warehouse stock and charging a customer's payment), a single local ACID transaction cannot cover both. Two-phase commit (2PC / XA) locks resources across participants until a coordinator commits, coupling availability and latency across services.

A Saga (topic 43) replaces the distributed lock with a sequence of local transactions coordinated by an explicit state machine:
- Each forward step commits its own local transaction immediately and records the new saga state (`STARTED -> STOCK_RESERVED -> COMPLETED`).
- If a later step fails (for example, payment is declined after stock was reserved), the saga cannot "roll back" the already-committed stock transaction; instead it transitions to `COMPENSATING` and runs an explicit **compensating transaction** (releasing the reserved units) until it reaches `COMPENSATED`.
- Because the orchestrator itself can crash between steps, saga state is persisted in a database (`saga_instances`), and every forward step and compensating step must be **idempotent** so a recovery worker can safely resume any incomplete saga.

## 2. Implementation

Isolated lab in the test tree of `warehouse-service` (`SagaTest`, unit 9.4, real `postgres:16.15-alpine` container via Testcontainers):
- Explicit `SagaState` enum: `STARTED`, `STOCK_RESERVED`, `COMPLETED`, `COMPENSATING`, `COMPENSATED`.
- Tables:
  - `saga_instances (saga_id PRIMARY KEY, stock_id, quantity, amount_cents, state, failure_reason)`
  - `saga_stock (stock_id PRIMARY KEY, available CHECK (available >= 0))`
  - `saga_reservations (saga_id PRIMARY KEY, stock_id, quantity, status)` (`RESERVED` or `RELEASED`)
  - `saga_payments (saga_id PRIMARY KEY, amount_cents)`
- Forward steps:
  - `reserveStockStep(sagaId)`: inserts `saga_reservations` with `ON CONFLICT DO NOTHING`; only when `inserted == 1` does it subtract `quantity` from `saga_stock`, then sets `state = STOCK_RESERVED`.
  - `chargePaymentStep(sagaId, paymentDeclined)`: if declined, sets `state = COMPENSATING` and records `failure_reason`; otherwise inserts `saga_payments` (`ON CONFLICT DO NOTHING`) and sets `state = COMPLETED`.
- Compensation and recovery:
  - `compensateStockStep(sagaId)`: runs `UPDATE saga_reservations SET status = 'RELEASED' WHERE saga_id = ? AND status = 'RESERVED'`; only when `released == 1` does it add `quantity` back to `saga_stock`, then sets `state = COMPENSATED`.
  - `recoverIncompleteSagas()`: queries `saga_instances WHERE state IN ('STARTED', 'STOCK_RESERVED', 'COMPENSATING')` and drives each stuck saga through `compensateStockStep` to `COMPENSATED`.

## 3. Measured observation

Automated test (`SagaTest`, 3 tests, 4.742 s on real PostgreSQL):

| Scenario | State transitions | Final stock (started at 10, order quantity 4) |
|---|---|---|
| Happy path (stock available, payment succeeds) | `STARTED -> STOCK_RESERVED -> COMPLETED` | `6` |
| Payment declined after stock is reserved | `STARTED -> STOCK_RESERVED -> COMPENSATING -> COMPENSATED` | `10` (4 reserved units restored) |
| Orchestrator crashes in `STOCK_RESERVED` (stock at `6`), then `recoverIncompleteSagas()` runs, then `compensateStockStep()` is called a second time | `STARTED -> STOCK_RESERVED -> COMPENSATING -> COMPENSATED` | `10` after recovery; still `10` after second compensation call (no double refund to `14`) |

## 4. Reproduction

In `apps/warehouse-service`:

```bash
./mvnw --batch-mode test -Dtest=SagaTest
```

## 5. Failure modes and edge cases

- **Lack of isolation (dirty reads across steps)**: between `STOCK_RESERVED` and `COMPENSATED`, other transactions see the stock at `6` even though the order ultimately failed. Reserving into a separate `reserved` column or `saga_reservations` table makes the pending state explicit instead of hiding it as a completed withdrawal.
- **Compensation cannot fail permanently**: if a compensating step fails due to a transient error, the recovery worker must keep retrying it until it succeeds or alerts an operator; a saga stuck in `COMPENSATING` holds reserved inventory until resolved.
- **Pivot step**: the step after which compensation is no longer possible (or after which subsequent steps are guaranteed to succeed) must run after all fallible steps such as payment authorization.

## 6. Interview questions

- How does a Saga differ from two-phase commit (2PC) in terms of locking and isolation?
- Why must both forward steps and compensating steps in a Saga be idempotent?
- What happens if the Saga orchestrator crashes right after reserving stock and before charging payment?
- How do you prevent another caller from seeing a temporary Saga reservation as a permanent stock reduction?
