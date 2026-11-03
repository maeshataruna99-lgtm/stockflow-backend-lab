# Guidebook 12: Redis Eviction

## 1. Concept

Redis keeps everything in memory. `maxmemory` caps that memory; `maxmemory-policy` decides what happens when a write would exceed it:

- `noeviction`: refuse the write with an `OOM` error; reads and deletes still work. Nothing is ever dropped.
- `allkeys-lru`: drop the least recently used key, any key.
- `allkeys-random`: drop a random key.
- `volatile-lru`: drop the least recently used key among those that have a TTL. Keys without a TTL are never dropped, so if none have a TTL it behaves like `noeviction`.
- (`allkeys-lfu` and `volatile-ttl` exist and were not run.)

Redis does not track exact recency. It samples `maxmemory-samples` keys (5 by default, 10 here) and drops the oldest of the sample, and it measures age in whole seconds.

## 2. The lab

- Capped Redis: service `redis-lab` in `compose.labs.yaml`, profile `eviction`, image `redis:7.4.12-alpine`, `--maxmemory` from `LAB_REDIS_MAXMEMORY` (default 8mb) and `--maxmemory-policy` from `LAB_REDIS_POLICY`. It is separate from the core Redis on purpose: the core Redis also holds the gateway rate-limit buckets, and evicting those would reset clients' quotas.
- Experiment: `labs/caching/eviction-lab.sh <policy> [maxmemory] [cold-keys] [ttl]` writes 50 hot keys, then 12000 cold keys of 1 KB in batches of 1000 one second apart, and reads the 50 hot keys after every batch. It prints writes refused, hot and cold keys left, `evicted_keys` and memory.
- Automated version (`RedisEvictionPolicyTest`, 5 tests, Testcontainers, 2 MB cap, 3000 cold keys) asserts the same behaviors per policy.
- Application effect (`ProductCacheFullRedisTest`): against a full `noeviction` Redis, product reads still return the right answer from the database, the refused cache write is counted in `catalog.cache{result=error}`, and caching resumes once keys are deleted.

## 3. Measured results

Redis 7.4.12, `maxmemory` 8 MB, 12000 cold keys of 1 KB, one run per row on one machine (the runs are repeatable, not a benchmark):

| Policy | TTL on keys | Writes refused (OOM) | Hot keys left | Cold keys left | `evicted_keys` |
|---|---|---|---|---|---|
| noeviction | none | 6603 | 50/50 | 5397 | 0 |
| allkeys-lru | none | 0 | 50/50 | 5367 | 6699 |
| allkeys-random | none | 0 | 8/50 | 5409 | 10785 |
| volatile-lru | none | 11951 | 50/50 | 5415 | 0 |
| volatile-lru | 3600 s | 321 | 49/50 | 5250 | 6573 |

Reading it:
- `noeviction` keeps everything and stops accepting writes after about 5400 keys; a cache in front of a database should not do that, the cache would fill with old keys and refuse new ones.
- `allkeys-lru` keeps all 50 hot keys and drops cold ones, which is what a cache wants.
- `allkeys-random` lost 42 of 50 hot keys: it cannot tell hot from cold.
- `volatile-lru` is the same as `noeviction` when keys have no TTL (11951 refused). With TTLs it evicts, but 321 writes were still refused with OOM in this run. The likely cause is that Redis limits how much work it spends evicting per command (`maxmemory-eviction-tenacity`), so a fast writer can briefly outrun it; I did not isolate that.

The very first version of the script wrote everything within about a second. Redis measures key age in whole seconds, so LRU could not separate hot from cold: `volatile-lru` with TTL left 0 of 50 hot keys while the automated test, which is slower, kept them. The script now sleeps one second between batches.

## 4. What this means for this project

- Product cache keys carry a TTL (60 s) and rate-limit keys expire too, so `volatile-lru` and `allkeys-lru` would both work for them; `allkeys-lru` also protects against a key that someone forgot to give a TTL.
- The core Redis runs with no `maxmemory` (policy `noeviction`, confirmed with `CONFIG GET`): memory use is unbounded. Capping it needs a decision, because the gateway shares it: with `allkeys-lru` an eviction removes a client's bucket (their quota resets, the limiter fails open for them); with `noeviction` the limiter's writes fail with OOM and it fails open for everyone. Not changed in this unit.
- A cache must treat an evicted key as a miss. It does: the miss path reloads from the database (`ProductCache.getOrLoad`).

## 5. Failure modes and edge cases

- Redis can exceed `maxmemory` slightly (8.08 MB with `noeviction` in the table).
- Large values and many clients' buffers also count toward the limit.
- With replication, eviction on the primary becomes deletes on the replicas; not exercised here.
- A hot key that is evicted and not re-read is not restored; the next read is a miss.

## 6. Interview questions

- Why do keys without a TTL make `volatile-lru` behave like `noeviction`?
- Why is Redis LRU only approximate, and what does `maxmemory-samples` trade?
- When would you choose `noeviction` for Redis anyway?
- What breaks when a cache and a rate limiter share one Redis with `allkeys-lru`?
