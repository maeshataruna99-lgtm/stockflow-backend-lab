# Guidebook 11: Cache-Aside, Invalidation and Redis Failure (catalog product read)

## 1. Concept

Cache-aside: the application asks Redis first; on a miss it reads the database and puts a copy in Redis with a TTL. The database stays the source of truth and Redis only holds a copy that may be dropped at any time.

Two things make a cache correct:
- Invalidation: when the row changes, the copy must go. It must go after the database commit. If the key is deleted before the commit, a reader can reload the old row and re-cache it; if the transaction rolls back, a valid entry was deleted for nothing.
- Failure behavior: Redis being slow or down must not break reads or writes. The TTL is the upper bound on staleness when an eviction is lost.

## 2. Implementation (catalog-service, `GET /api/v1/products/{id}`)

- `ProductCache.getOrLoad(id, loader)`: key `catalog:product:v1:<id>`, value the JSON of `ProductResponse`, TTL `stockflow.cache.product-ttl` (60 s default). A hit returns the copy; a miss runs the loader (database) and stores the result.
- `ProductCache.evictAfterCommit(id)` is called by `ProductService.update`: it registers a transaction synchronization, so the key is deleted only after commit. A rolled-back update never evicts.
- A missing product (404) is never cached.
- Redis failure: a Redis error on read is counted (`catalog.cache{result=error}`), logged, and the request is served from the database. The result is not written back after a failed read, so an outage costs one timeout per request, not two. A failed eviction is counted and logged; the old copy lives until its TTL.
- Timeouts: `spring.data.redis.timeout` and `connect-timeout` are 300 ms. The Redis health indicator is off for catalog (`management.health.redis.enabled: false`), so an outage degrades latency, not readiness.
- Switch: `stockflow.cache.enabled: false` sends every read to the database.
- Counter `catalog.cache` with tags `cache=product` and `result=hit|miss|error`.
- What is not cached: product lists and searches (invalidating every list a change touches is a separate problem), and other entities.

## 3. Reproduction

Automated (real Redis `redis:7.4.12-alpine` and PostgreSQL containers):

```bash
./mvnw --batch-mode test -Dtest=ProductCacheTest,ProductCacheRedisDownTest,RedisTimeoutConfigTest
```

- `ProductCacheTest`: first read is a miss and creates the key with a TTL of at most 30 s, second read is a hit; a PATCH removes the key and the next read returns the new value; a rolled-back update keeps the entry; a 404 is not cached.
- `ProductCacheRedisDownTest`: with nothing listening on the Redis port, a read returns 200 from the database with exactly one error counted, and a PATCH still returns 200.
- `RedisTimeoutConfigTest`: the timeouts and the health setting are in `application.yml`.

Live: `playground/http/product-cache.http`. Run the lab stack (`docker compose -f compose.yaml -f compose.labs.yaml up --build -d`), read a product twice through the gateway, and read the counters with an admin token:

```bash
docker compose -f compose.yaml -f compose.labs.yaml exec -T catalog wget -qO- "--header=Authorization: Bearer <admin token>" "http://localhost:8081/actuator/metrics/catalog.cache?tag=result:hit"
```

```bash
docker compose exec -T redis redis-cli TTL catalog:product:v1:1
```

## 4. Failure modes and edge cases

- Stale window: a reader that loaded the old row just before a commit can write it back right after the eviction. That copy lives until its TTL. A shorter TTL narrows the window; a version column would close it.
- A lost eviction (Redis down during a PATCH) leaves the old copy for up to the TTL.
- Cold start: the first request after a restart took about 1.8 s once (JIT, connection setup). The gateway response timeout is 1 s, so it retried the GET and the catalog counted two misses for one request, an accidental small stampede (see the stampede lab).
- A corrupt cache entry is counted as an error, replaced from the database, and not served.
- Redis is shared with the gateway rate limiter; an eviction policy that removes keys under memory pressure affects both (eviction lab).

## 5. Trade-offs

- Cache-aside is simple and tolerant of Redis loss, at the price of a stale window and a miss penalty.
- Evicting beats updating the copy on write: one code path, no risk of caching a value the database would reject.
- A long TTL saves database reads and lengthens the worst stale case.

## 6. Interview questions

- Why delete the key after the commit and not before?
- What happens to correctness when Redis is down, and what does it cost in latency?
- Why not cache a 404?
- What is the race between a reader and a writer around the eviction, and how would you close it?
- Why is the Redis health check off for a service that only uses Redis as a cache?
