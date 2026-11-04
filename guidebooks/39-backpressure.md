# Guidebook 39: Backpressure and Bounded Queues

## 1. Concept

When a producer generates work faster than a consumer can process it, the difference has to go somewhere. An **unbounded queue** (`Executors.newFixedThreadPool`, which uses an unbounded `LinkedBlockingQueue` with capacity `Integer.MAX_VALUE`, or a broker queue without `x-max-length`) absorbs the burst in memory until the process runs out of heap (`OutOfMemoryError`) and every queued item suffers growing latency.

**Backpressure** (topic 47) bounds the queue and forces an explicit choice when the queue is full:
1. **Slow the producer (`CallerRunsPolicy` / blocking put)**: when the bounded queue is full, the submitting thread executes the task itself (or blocks until a slot frees up). Throughput naturally matches consumer capacity and zero items are dropped, at the cost of blocking the producer thread.
2. **Shed load (`AbortPolicy` / `Semaphore.tryAcquire` / HTTP 429 or 503)**: when the workers and bounded queue are full, excess submissions fail immediately. Accepted requests finish within predictable latency instead of queuing behind thousands of timed-out requests.
3. **Broker queue bounds (`x-max-length` on RabbitMQ)**: caps how many messages a queue holds when consumers fall behind, either dropping/dead-lettering the oldest messages (`drop-head`, `x-first-death-reason: maxlen`) or refusing new publishes (`reject-publish`).

## 2. Implementation

Automated tests in `apps/warehouse-service`:
- `BackpressureTest` (unit 9.7):
  - `callerRunsBackpressureThrottlesProducerAndDropsZeroItems`: `ThreadPoolExecutor` with `2` workers, bounded `ArrayBlockingQueue(4)`, and `CallerRunsPolicy`, fed `30` tasks that each take `15 ms`.
  - `abortPolicyShedsExcessLoadImmediatelyWhenQueueFills`: `ThreadPoolExecutor` with `2` workers, bounded `ArrayBlockingQueue(5)`, and `AbortPolicy`, fed `20` tasks while the `2` workers are held busy.
  - `semaphoreAdmissionControlCapsInFlightWork`: `Semaphore(3)` non-blocking `tryAcquire()` over `10` concurrent arrivals.
- `RabbitMqDlqTest.boundedQueueCapsDepthAndDeadLettersOverflowWithMaxlenReason` and `labs/messaging/dlq-lab.sh`:
  - RabbitMQ queue `stock.bounded` / `lab.bounded` declared with `x-max-length: 3` and `x-dead-letter-exchange`, fed `5` messages while no consumer reads.

## 3. Measured observation

Automated tests (`BackpressureTest`, 0.416 s, and `RabbitMqDlqTest`) and live lab (`labs/messaging/dlq-lab.sh`):

| Policy and bounds | Load | Measured result |
|---|---|---|
| `CallerRunsPolicy`, 2 workers, queue capacity 4 | 30 tasks (15 ms each) submitted in a tight loop | `completed = 30` (0 dropped), `ranOnProducerThread = 8` (run 1) and `9` (run 2), `maxObservedQueueDepth = 4` (never exceeded capacity 4) |
| `AbortPolicy`, 2 workers, queue capacity 5 | 20 tasks submitted while workers are busy | `accepted = 7` (`2` active workers + `5` queued), `rejected = 13` (`RejectedExecutionException` immediately) |
| `Semaphore(3)` non-blocking `tryAcquire()` | 10 concurrent arrivals | `acquired = 3`, `shed = 7`; releasing 3 restores `availablePermits = 3` |
| RabbitMQ queue with `x-max-length: 3` and `x-dead-letter-exchange` | 5 messages (`cap-1..5`) published with no active consumer | Queue keeps the 3 newest (`cap-3`, `cap-4`, `cap-5`); the 2 oldest (`cap-1`, `cap-2`) are routed to DLQ with `x-first-death-reason: maxlen` |

## 4. Reproduction

In `apps/warehouse-service`:

```bash
./mvnw --batch-mode test -Dtest=BackpressureTest,RabbitMqDlqTest
```

Live against RabbitMQ:

```bash
labs/messaging/dlq-lab.sh
```

## 5. Failure modes and edge cases

- `CallerRunsPolicy` on an HTTP server's event-loop or acceptor thread stalls the thread that accepts or dispatches sockets, blocking unrelated endpoints on the same server; for shared request threads, fast load shedding (`AbortPolicy` / semaphore -> `503 Service Unavailable`) is safer than running slow work on the caller thread.
- A bounded queue that is too large still allows multi-second queueing delays (Little's Law: `maxWait = queueCapacity / throughput`); size the queue from the latency budget, not from available RAM.

## 6. Interview questions

- Why is `Executors.newFixedThreadPool(n)` dangerous under sustained overload?
- When should an overloaded service block the producer (`CallerRunsPolicy`) versus fail fast (`AbortPolicy` / HTTP 503)?
- How do you size a bounded work queue given a target latency budget and worker throughput?
- What is the difference between RabbitMQ's `drop-head` and `reject-publish` overflow modes when `x-max-length` is reached?
