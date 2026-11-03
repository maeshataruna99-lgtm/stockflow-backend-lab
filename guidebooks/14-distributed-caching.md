# Guidebook 14: Distributed Caching (two catalog instances, one Redis)

## 1. Concept

With more than one instance of a service, an in-process cache gives each instance its own copy, so an update on one instance leaves stale copies on the others. A shared cache (Redis) keeps one copy for all instances: one instance fills it, every instance reads it, one eviction removes it for all.

What stays per instance: anything held in the JVM. Here that is the single-flight map (guidebook 13), the Micrometer counters and the connection pools.

## 2. Implementation (catalog-service)

No new production code. The cache key `catalog:product:v1:<id>` lives in the shared Redis (`SPRING_DATA_REDIS_HOST: redis` for `catalog` and `catalog-2`), the database stays the source of truth, and `ProductService.update` evicts after commit.

- Instance A loads and stores; instance B finds the key: a hit, no database read on B.
- An update on either instance deletes the key after commit; the next read on any instance loads the new row.
- A herd that arrives while the key is missing costs at most one database load per instance, because single-flight does not cross instances. N instances: up to N loads. A distributed lock (`SET key NX PX`) would make it one; not done.

## 3. Measured observation

Counter `catalog.cache{result=load}` is a database read; `hit` is a Redis hit.

| Check | Where | Result |
|---|---|---|
| A fills, B reads | test | A load +1, B hit +1, B load +0 |
| Update on A, read on B | test | key gone, B returns the new name, B load +1 |
| 40 reads split 20/20 over both instances, key missing | test | A load 1, B load 1 |
| A fills, B reads | lab stack | catalog load +1, catalog-2 load +0 and hit +1 |
| PATCH through the gateway | lab stack | 200, key gone, both instances return the new description, 1 load in total |
| 40 reads split 20/20, key missing | lab stack | all 200, catalog load +1, catalog-2 load +1 |

One run per row on one machine. The herd row shows the per-instance limit, not a benchmark. In the test, instance B is a second application context in the same JVM against the same PostgreSQL and Redis containers.

## 4. Reproduction

Automated:

```bash
./mvnw --batch-mode test -Dtest=ProductCacheTwoInstancesTest
```

Live (log in as root through the gateway and pass the token):

```bash
docker compose -f compose.yaml -f compose.labs.yaml up --build -d
```

```bash
labs/caching/distributed-lab.sh <admin-token> 40
```

## 5. Failure modes and edge cases

- Redis is the single shared dependency: when it is down every instance reads the database (guidebook 11), so the database load grows with the number of instances and requests.
- The stale window of guidebook 11 applies across instances: a reader that loaded the old row just before a commit on another instance can re-store it after the eviction; the TTL bounds it.
- Instances must agree on the key format and the JSON shape of `ProductResponse`. The `v1` in the key is the version to bump when the shape changes during a rolling deploy, otherwise old and new instances read each other's entries.
- Clock and TTL: the TTL is set by whichever instance stores the key.

## 6. Interview questions

- Why does an in-process cache break with two instances, and what does a shared cache change?
- Why does per-instance single-flight not stop a stampede across instances, and what would?
- How can a rolling deploy corrupt a shared cache, and what does the key version do about it?
- What happens to the database when the shared cache is lost?
