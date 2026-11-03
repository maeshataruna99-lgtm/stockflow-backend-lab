# Guidebook 13: Cache Stampede and Single-Flight

## 1. Concept

A cache stampede (thundering herd) happens when many requests ask for the same key at the moment it is not in the cache: right after a restart, after an eviction, or when a popular key's TTL runs out. Every one of them misses and every one reads the database for the same row. The cache that should shield the database sends it a burst instead.

Defenses:
- Single-flight (request coalescing): the first request loads, the others wait for its result. Used here.
- A distributed lock on the key (for example `SET key NX PX`): same idea across instances, with a lock to expire and a Redis dependency on the miss path. Not done.
- TTL jitter: spread expiry times so many keys do not expire together. Not done (only one entry per product, mass expiry is not the case here).
- Serving the stale copy while one request refreshes it. Not done.

## 2. Implementation (catalog-service)

- `ProductCache.getOrLoad`: on a miss, a `ConcurrentHashMap<Long, CompletableFuture<ProductResponse>>` decides who loads. The request that adds the entry is the leader; it looks once more in Redis (the previous leader may have just stored the product), then loads from the database and stores. The others find the entry and wait for the leader's future.
- If the leader fails (for example 404), the waiters get the same failure.
- Switch: `stockflow.cache.single-flight` (default true). In the lab stack `LAB_SINGLE_FLIGHT=false` turns it off for the catalog.
- Counter `catalog.cache{result=...}`: `load` = the database was read, `coalesced` = a miss answered by another request's load, plus the existing `hit`, `miss`, `error`.
- Scope: per catalog instance. Two instances can still load once each (unit 6.4). Waiters do not hold a database connection while they wait, because only the load runs in a transaction.

## 3. Measured observation

40 identical GET requests released at the same moment for a product that is not cached, product 1 or a fresh one. "Loads" is the `catalog.cache{result=load}` counter, i.e. database reads.

| Setup | Single-flight | Loads for 40 requests |
|---|---|---|
| Test (MockMvc, 40 threads, 10-connection pool) | on | 1 |
| Test, same | off | 40 |
| Lab stack, wget from the gateway container, 3 rounds | on | 1, 1, 1 |
| Lab stack, same, 3 rounds after a warm-up round | off | 7, 6, 5 |
| Lab stack, same, warm-up round straight after a restart | off | 40 |
| Lab stack, warm-up round | on | 1 |

Notes on the numbers:
- With protection off the count depends on how closely the requests arrive. The test releases all threads at one instant and all 40 hit the database; the lab starts 40 wget processes one after another, so the first loads finish and fill the cache while later requests are still starting. A real herd after a restart looks like the 40.
- While building this lab, the first test run showed 10 loads, not 40. The product read was wrapped in one transaction, so each request took a pooled connection (10) before it even checked the cache, and the pool throttled the herd by accident. A test then proved a cache hit also took a connection, which is wrong for a cache; the transaction now covers only the load, and the unprotected herd is 40.
- One run per row on one machine; the figures show the order of magnitude, not a benchmark.

## 4. Reproduction

Automated (real Redis and PostgreSQL containers):

```bash
./mvnw --batch-mode test -Dtest=ProductCacheStampedeTest,ProductCacheStampedeUnprotectedTest
```

Live, against the lab stack (log in as root through the gateway first and pass the token):

```bash
docker compose -f compose.yaml -f compose.labs.yaml up --build -d
```

```bash
labs/caching/stampede-lab.sh <admin-token> 40 3
```

To see the herd, restart the catalog with the protection off and run the script again:

```bash
LAB_SINGLE_FLIGHT=false docker compose -f compose.yaml -f compose.labs.yaml up -d catalog
```

Restore with `docker compose up -d catalog`.

## 5. Failure modes and edge cases

- A leader that is slow or stuck delays all its waiters; there is no timeout on the wait other than the gateway's.
- Single-flight is per instance. Across N instances a herd costs up to N loads.
- The waiter returns the leader's result even if a PATCH committed between the leader's read and the answer; the entry is evicted by the PATCH and the next read is fresh, so staleness stays within one request.
- When Redis is down, single-flight still limits the database to one read per instance at a time per product, which helps during an outage.

## 6. Interview questions

- Why can a cache make a database outage worse?
- What is the difference between single-flight, a distributed lock and serving stale data?
- Why does a pool of 10 connections hide a stampede, and why is that not a defense?
- What does per-instance single-flight not protect against?
