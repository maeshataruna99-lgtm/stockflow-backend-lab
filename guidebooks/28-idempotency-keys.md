# Guidebook 28: Idempotency Keys (warehouse stock writes)

## 1. Concept

An operation is idempotent when doing it twice has the same effect as doing it once. `GET` and `PUT` are idempotent by definition; a `POST` that withdraws stock is not: sent twice, it withdraws twice. Networks make repeats unavoidable. A client that sent a request and got no answer (timeout, dropped connection) cannot know whether the server did the work, so it either gives up (maybe losing the write) or sends it again (maybe doing it twice).

An idempotency key turns the retry into something safe. The client generates a unique key per intended operation and sends it with the request. The server remembers the key and the response; a request with a key it has already completed gets the stored response back and does no second write. The key must also be tied to the request: the same key with a different request is a client bug (or an attack) and must be refused, otherwise the client would believe it got what it asked for.

## 2. Implementation (warehouse-service)

- `POST /api/v1/stock/{id}/withdraw` and `POST /api/v1/stock/transfer` accept an optional header `Idempotency-Key` (8 to 80 characters of letters, digits and `_ . : -`; anything else is a 400). Without the header nothing changes.
- Table `idempotency_keys` (Flyway V7): the key (primary key), a SHA-256 fingerprint, the response body, the creation time.
- `IdempotencyService.execute` runs in one transaction with the write: it claims the key with `INSERT ... ON CONFLICT DO NOTHING`. If that inserted a row it runs the write and stores the response. If a row already existed it compares fingerprints: equal means return the stored response with the header `Idempotent-Replayed: true`; different means a 422 "Idempotency key reused".
- The fingerprint covers the caller (the token subject), the operation, and the request (for withdraw the stock id and quantity). So another caller, another stock row or another operation under the same key is refused, and nobody can read someone else's stored response by guessing a key.
- Concurrency: two requests with one key at the same moment: the second `INSERT` waits for the first transaction (bounded by the same lock timeout as the other stock writes) and then finds its row, so the write runs once.
- A failed write rolls the key back with it (a 404 or 409 stores nothing), so the client may send the same key again after fixing the cause. Only successful responses are stored.
- Keys older than 24 hours (`stockflow.stock.idempotency-retention`) are deleted by an hourly job.

## 3. Measured observation

Tests (`IdempotencyKeyTest`, real PostgreSQL) and the live lab (`labs/resilience/idempotency-lab.sh`):

| Scenario | Result |
|---|---|
| Same key, same request, twice | both 200 with the same body; the second has `Idempotent-Replayed: true`; stock 10 - 3 = 7 |
| Same key, quantity 4 instead of 3 | 422 "Idempotency key reused"; stock still 7 |
| Same key on another stock row, on the transfer endpoint, or from another caller | 422 each; stock unchanged |
| 20 simultaneous requests, one key | 1 executed and 19 replayed; all 200 with the same body; stock 7 |
| Different keys, and no key | each key executes; two requests without a key withdraw twice |
| Withdraw 9 from a stock of 5 with a key, twice | 409 both times; no row left in `idempotency_keys` |
| Bad keys (`short`, with a space, 81 characters, with `;`) | 400 each |
| Purge: one key made 3 days old | removed by `purgeOlderThan(24 h)`; the recent key stays |
| Retry after a lost response, with a key (`SafeRetryTest`) | stock 10 - 3 = 7 |
| The same retry without a key | stock 10 - 6 = 4 |

The live results are in the Phase 8 progress log in docs/STATUS.md.

## 4. Reproduction

Automated, in `apps/warehouse-service`:

```bash
./mvnw --batch-mode test -Dtest=IdempotencyKeyTest,SafeRetryTest
```

Live (log in as root through the gateway and pass the token):

```bash
labs/resilience/idempotency-lab.sh <admin-token>
```

## 5. Failure modes and edge cases

- The stored response is a copy: if the stock changes afterwards a replay still shows the original answer (that is the point).
- The key lives in the same database as the data. That makes "claim, write, store" atomic, but it also means the key table is only as available as the database.
- Only successful responses are stored. A client that gets a 409 and retries the same key re-runs the check; this is a choice, some APIs store errors too.
- The gateway never retries a `POST` by itself (unit 5.6). A client that retries a `POST` must send a key, or accept the double write shown above.
- The retention must be longer than the longest time a client may retry, or a late retry is executed as a new request.
- The key is not scoped by tenant beyond the caller subject; two different keys sent twice are two operations.
- An in-progress request is visible to a second request only by waiting on the primary key; a very slow first request makes the second fail with a lock timeout (503) rather than run twice.

## 6. Interview questions

- Why is a retry of a `POST` dangerous, and what makes it safe?
- Why must the key be bound to the request body and the caller?
- How does the server make "claim the key and do the work" atomic, including when two requests arrive together?
- What happens to the key when the operation fails?
- How long should keys be kept?
