# Guidebook 21: Indexes and EXPLAIN ANALYZE (catalog product list)

## 1. Concept

An index is a separate structure that lets PostgreSQL find rows without reading the whole table. The planner decides per query, from table statistics (`ANALYZE`) and cost estimates, whether to use one. An index that exists is not an index that is used, and an index that is used is not always faster.

- B-tree: equality and range on one column (`category_id = 17`, `sku = 'X'`). The default, also behind primary keys and unique keys. It cannot serve `LIKE '%term%'` (the match can start anywhere).
- GIN with `pg_trgm`: indexes the three-character pieces of a string, so `LIKE '%term%'` and `ILIKE` can use it. Needs the extension; costs space and write time.
- Selectivity decides: when a filter matches a small share of the table, an index wins; when it matches a large share, a sequential scan is cheaper.
- `EXPLAIN` shows the plan the planner chose; `EXPLAIN (ANALYZE, BUFFERS)` runs the query and shows what happened: real time per node, rows, pages read. Only the second one is evidence of speed.

Reading a plan: the nodes `Seq Scan` (reads the table), `Index Scan` and `Bitmap Index Scan` + `Bitmap Heap Scan` (use an index), `Limit` (stops early), `Aggregate` and `Gather` (parallel count).

## 2. Implementation (catalog-service)

`GET /api/v1/products` sends one list query and one count query per page, both with three optional filters in the "catch-all" form: `(cast(? as bigint) is null or p.category_id = ?)` and the same for `active` and for a contains-search over `lower(name)` and `lower(sku)`. The schema from V1 had only the primary keys and the unique keys (`sku`, `code`); the foreign keys `category_id` and `unit_id` have no index.

Migration `V3__product_indexes.sql` adds:
- `products_category_id_idx` (btree on `category_id`),
- `products_name_trgm_idx` and `products_sku_trgm_idx` (GIN, `gin_trgm_ops`, on `lower(name)` and `lower(sku)`), with `CREATE EXTENSION IF NOT EXISTS pg_trgm`.

`pg_trgm` is a trusted extension, so the database owner (`catalog_app`) can create it: checked on the compose database, the migration ran as `catalog_app` and `pg_extension` lists `pg_trgm 1.6`. Not added: an index on `unit_id` (not measured here, no query filters on it).

## 3. Measured observation

`ProductIndexPlanTest` runs on its own PostgreSQL 16.15 container with the catalog migrations and 200,000 products in 100 categories (2,000 products per category, the "dense" category 17) plus one "rare" category of 50 products stored at the end of the table. Each row below is a real `EXPLAIN (ANALYZE, BUFFERS)` of the statement Hibernate sends, parameters bound as `$1..$4`; the time is the median of 5 runs after a warm-up. The machine has 8 cores and PostgreSQL ran in Docker on it. Two runs of the whole class (run 1 / run 2):

| Query | No index (V2) | Index from V3 | Plan before | Plan after |
|---|---|---|---|---|
| Count, dense category | 26.5 / 34.6 ms | 3.7 / 6.1 ms | Seq Scan, parallel | Bitmap Index Scan on `products_category_id_idx` |
| List page of 20, rare category | 33.5 / 35.3 ms | 0.20 / 0.30 ms | Seq Scan, parallel | Bitmap Index Scan on `products_category_id_idx` |
| List page of 20, dense category | 0.45 / 0.41 ms | 1.4 / 2.1 ms | Seq Scan stopped by the `Limit` | Bitmap Index Scan (slower) |
| List page of 20, search `f00ba` | 328 / 361 ms | 0.68 / 1.03 ms | Seq Scan | BitmapOr of the two trigram indexes |
| Count, search `f00ba` | 160 / 203 ms | 0.57 / 0.55 ms | Seq Scan, parallel | BitmapOr of the two trigram indexes |

What the table says:
- The search went from a full scan to an index lookup: about 350 to 480 times faster for the list and 280 to 370 times for the count. The gain is far larger than the run-to-run noise (the same plan differed by 10 to 65 percent between the two runs).
- A btree on `category_id` did not change the search plans at all (stage 1 of the test: Seq Scan, 328 ms against 328 ms); only the trigram indexes did.
- The count of a category and the list of a rare category use the `category_id` index and are 6 to 7 times and over 100 times faster.
- The list of a dense category got slower with the index (0.4 ms to 1.4 to 2.1 ms). Without the index, the `Limit` lets the sequential scan stop after about 2,000 rows, because every hundredth row belongs to the category; the bitmap index scan has to build its bitmap first. Both are small, and the same index is what makes a rare category fast, but an index is not free for the query it does not serve.
- With the plan forced to generic (`force_generic_plan`), the catch-all list stays a Seq Scan even with the index present (0.5 to 1.0 ms), because a generic plan cannot assume `$2 is not null`. With the default `plan_cache_mode = auto`, the count of the dense category used the index (Bitmap Heap Scan) in all 10 executions of a prepared statement in both runs, so PostgreSQL did not switch to the generic plan here. That is one query and one dataset; it does not prove the switch never happens.
- The last stage drops the hand-made indexes and applies the real migration V3: same index names and the same plans.

Not measured: index sizes on disk, the cost of the indexes on `INSERT` and `UPDATE`, the build time of `CREATE INDEX`, anything on `unit_id`, and a search term shorter than three characters (trigram indexes work on three-character pieces).

## 4. Reproduction

Automated (starts its own PostgreSQL container, about one minute; prints every plan as lines starting with `PLAN`):

```bash
./mvnw --batch-mode test -Dtest=ProductIndexPlanTest
```

On the compose database, as the service role (log in with the password from `.env`):

```bash
docker exec -it stockflow-postgres psql -U catalog_app -d catalogdb -c "EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM products WHERE category_id = 1"
```

The seed has three products, so the planner will prefer a Seq Scan there; the lab dataset is what shows the difference.

## 5. Failure modes and edge cases

- The planner needs statistics: after a bulk load without `ANALYZE` it can choose a bad plan. The test runs `ANALYZE` after loading and after each index.
- `CREATE INDEX` in a Flyway migration locks writes to the table while it builds. That is fine on this small demo table; on a large live table use `CREATE INDEX CONCURRENTLY`, which cannot run inside a transaction (unit 7.9, migrations).
- Every index slows `INSERT`, `UPDATE` of the indexed columns and `DELETE`, and takes disk space; the GIN trigram indexes are the heaviest. Indexes that no query uses are cost without benefit.
- The catch-all query form (`param is null or column = param`) works with an index only when the plan is built for the actual parameter values. A forced generic plan cannot; ORMs and drivers that cache plans can run into that.
- A sort order is not covered: the list query has no `ORDER BY` unless the caller asks for a sort; an index on the sort column would be a separate decision.
- `EXPLAIN ANALYZE` really runs the statement; do not run it on `UPDATE` or `DELETE` without a transaction you roll back.

## 6. Interview questions

- Why does a btree on one column not help a `LIKE '%term%'` search, and what does?
- When is a sequential scan cheaper than an index scan?
- What is the difference between `EXPLAIN` and `EXPLAIN ANALYZE`, and which one proves a performance claim?
- Why can a new index make one query slower?
- What does `CREATE INDEX CONCURRENTLY` change, and why can Flyway not run it in a transaction?
