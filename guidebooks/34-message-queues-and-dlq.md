# Guidebook 34: Message Queues, Retries and Dead-Letter Queues (RabbitMQ)

## 1. Concept

A message queue (topic 35) decouples producers from consumers in time: the producer publishes a message to an exchange, the broker routes it to a bound queue, and a worker consumes and acknowledges it at its own pace.

When a consumer fails to process a message, two opposite mistakes are common:
- Requeueing forever (`basic.nack` / `reject` with `requeue=true` on every failure): a single malformed or invalid message (a poison message) loops at the head of the queue forever, burning CPU and blocking or delaying healthy messages behind it.
- Dropping immediately on the first error (`requeue=false` with no dead-letter exchange): a transient failure destroys the message permanently.

A bounded retry policy paired with a dead-letter queue (DLQ, topic 36) solves both:
1. Transient failures are retried up to a fixed `maxAttempts` budget.
2. When `attempt >= maxAttempts` (or when the error is a permanent validation failure), the consumer rejects the message with `requeue=false`.
3. The work queue is configured with `x-dead-letter-exchange` (`stock.dlx`) and `x-dead-letter-routing-key` (`stock.dlq`). RabbitMQ automatically routes the rejected message to `stock.dlq` and stamps `x-death` / `x-first-death-reason: rejected` headers on it, clearing the work queue for subsequent valid messages while preserving the poison message for inspection and replay.

## 2. Implementation

Infrastructure (`compose.labs.yaml`):
- Optional service `rabbitmq` (`rabbitmq:3.13.7-management-alpine`) under `profiles: ["messaging"]`, on the internal `stockflow` network with healthcheck `rabbitmq-diagnostics -q ping`.

Automated test (`RabbitMqDlqTest` in `apps/warehouse-service`, real `rabbitmq:3.13.7-management-alpine` + `postgres:16.15-alpine` containers via Testcontainers) and live lab script (`labs/messaging/dlq-lab.sh`):
- Exchanges `stock.events` (`direct`) and `stock.dlx` (`direct`).
- Queue `stock.dlq` bound to `stock.dlx` with routing key `stock.dlq`.
- Queue `stock.work` bound to `stock.events` (`routing_key: stock.changed`) with arguments `x-dead-letter-exchange: stock.dlx` and `x-dead-letter-routing-key: stock.dlq`.
- Worker loop: reads a message from `stock.work`. If valid, claims `message_id` in `processed_events` (`INSERT ... ON CONFLICT DO NOTHING`) and updates `stock_ledger` in one PostgreSQL transaction, then acknowledges (`ack_requeue_false`). If invalid, increments the attempt counter for `message_id`; while `attempt < 3` it requeues (`reject_requeue_true`), and on attempt `3` it rejects without requeue (`reject_requeue_false`), routing the message to `stock.dlq`.

## 3. Measured observation

Automated test (`RabbitMqDlqTest`, 3 tests, 32.39 s including container startup) and live script (`labs/messaging/dlq-lab.sh`, 2 runs):

| Scenario | Result |
|---|---|
| Duplicate message `evt-dup-1` (`delta: -3`) published twice + `evt-2` (`delta: +5`) on initial stock 10 | `evt-dup-1` applied once, `evt-2` applied once; final stock `10 - 3 + 5 = 12`; `stock.dlq` empty |
| Poison message `evt-poison-99` (`{"stockId":1,"delta":"not-a-number"}`) followed by valid `evt-valid-after-poison` (`delta: -2`) | `evt-poison-99` attempted `3` times then routed to `stock.dlq` with `x-first-death-queue=stock.work` and `x-first-death-reason=rejected`; `evt-valid-after-poison` processed once (`12 - 2 = 10`) |
| Queue `stock.bounded` with `x-max-length: 3` and `x-dead-letter-exchange: stock.dlx`, 5 messages (`cap-1..5`) published | `stock.bounded` retains the 3 newest (`cap-3`, `cap-4`, `cap-5`); the 2 oldest (`cap-1`, `cap-2`) are dead-lettered to `stock.dlq` with `x-first-death-reason=maxlen` |

Implementation note found during testing: JDK 21 `HttpClient` defaults to HTTP/2 upgrade (`Upgrade: h2c`), which RabbitMQ's Cowboy management server closes with `EOF reached while reading`; setting `HttpClient.Version.HTTP_1_1` fixed it.

## 4. Reproduction

Automated, in `apps/warehouse-service`:

```bash
./mvnw --batch-mode test -Dtest=RabbitMqDlqTest
```

Live against the Compose `messaging` profile:

```bash
docker compose -f compose.yaml -f compose.labs.yaml --profile messaging up -d rabbitmq
```

```bash
labs/messaging/dlq-lab.sh
```

## 5. Failure modes and edge cases

- A DLQ without monitoring or a retention/replay process becomes a silent graveyard for failed data.
- Requeueing a poison message at the head of a single queue delays messages behind it until the retry budget is spent; delayed retry queues (per-delay TTL queues that dead-letter back to the work exchange) avoid blocking the head of the queue during backoff waits.
- When `x-max-length` uses the default `drop-head` overflow policy, RabbitMQ drops (and dead-letters if DLX is set) the oldest message at the head of the queue when a new message arrives.

## 6. Interview questions

- What happens to a queue when a consumer calls `nack(requeue=true)` on a message with a permanent schema error?
- How does RabbitMQ route a rejected or expired message to a dead-letter queue?
- Which headers does RabbitMQ attach to a dead-lettered message, and what do they tell an operator?
- Why must a message consumer still be idempotent even when dead-lettering is configured?
