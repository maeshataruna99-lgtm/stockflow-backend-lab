# Guidebook 20: N+1 Queries (catalog product list)

## 1. Concept

The N+1 problem: one query loads N rows (the "1"), then code touches a lazy relation of each row and the ORM runs one more query per row (the "N"). Each query is cheap and correct, so nothing fails; the cost grows with the page size and shows up as latency and database load.

In JPA, `@ManyToOne(fetch = LAZY)` leaves a proxy in place of the related entity; the first call on it (`getCode()`) runs a SELECT. Hibernate keeps loaded entities in the persistence context of the transaction, so a related row shared by many products is loaded once, and the real cost is the number of distinct related rows, not the number of products.

Fixes: load the relation in the same SELECT (`JOIN FETCH` or an entity graph), or select only the columns needed into a DTO projection, or fetch the related rows in one extra query (`IN (...)`, batch fetching). Fetching a collection with a join while paging is a different trap (Hibernate pages in memory); this case is about single-valued relations, which page fine in SQL.

## 2. Implementation (catalog-service)

`GET /api/v1/products` runs `ProductRepository.searchAll` and maps each `Product` with `ProductResponse.from`, which reads `getCategory().getCode()` and `getUnit().getCode()`. Both relations are lazy, so every product on the page with a category or unit not seen yet cost one query each.

The fix is `@EntityGraph(attributePaths = {"category", "unit"})` on `searchAll`. The JPQL is unchanged. Spring Data still derives the count query for the `Page` (without the fetch). Behavior of the endpoint did not change.

Not changed: `GET /api/v1/products/{id}` still loads the product with `findById` and then reads category and unit (3 statements for one product, a fixed number, not N+1; a cache hit costs none, guidebook 11).

## 3. Measured observation

`ProductListQueryCountTest` counts the SQL statements Hibernate prepared (`Statistics.getPrepareStatementCount()`, enabled with `hibernate.generate_statistics`) around one call of `ProductService.search`, with 30 products that each have their own category and their own unit (the worst case: nothing is shared in the persistence context).

| Page size | Before (lazy relations) | After (entity graph) |
|---|---|---|
| 30 | 62 | 2 |
| 5 | 12 | 2 |

Before: 1 page query + 1 count query + 30 category queries + 30 unit queries = 62; with a page of 5 the same pattern gives 2 + 5 + 5 = 12. After: 1 page query with both joins + 1 count query, for any page size. The "before" numbers come from the first run of the test against the unchanged query (the test failed with `expected: 2L but was: 62L`). With few distinct categories and units, the persistence context reuses the rows already loaded, so the extra statements are limited to the number of distinct ones (not measured here); the problem appears with many distinct related rows.

Counts of statements, not durations: no time claim is made. This unit has no compose lab; a compose smoke test only checks that the endpoint still answers correctly with the new query (see docs/STATUS.md).

## 4. Reproduction

Automated (real PostgreSQL container):

```bash
./mvnw --batch-mode test -Dtest=ProductListQueryCountTest
```

To see the problem again, remove the `@EntityGraph` line from `ProductRepository.searchAll` and rerun: the first test fails with 62 statements.

To see the SQL itself in any run, set `spring.jpa.show-sql=true` (or `logging.level.org.hibernate.SQL=DEBUG`).

## 5. Failure modes and edge cases

- `JOIN FETCH` or an entity graph on a collection (`@OneToMany`) together with paging makes Hibernate fetch everything and page in memory (warning HHH90003004). Fetch single-valued relations this way and load collections in a second query.
- Fetching more than needed has a cost too: a list that only needs `categoryCode` could select two columns into a DTO instead of two whole entities.
- An N+1 can hide behind a cache: the product-by-id cache of unit 6.1 hides the 3 statements of a single read, not the list.
- A test on statement counts must use data that defeats the persistence-context cache (distinct related rows), otherwise a small data set passes by accident.
- Statistics count prepared statements, including the count query of a `Page`. A page that is not full skips the count query.

## 6. Interview questions

- Why does an N+1 not show up in functional tests?
- Which fixes exist for N+1 and when does each one stop working?
- Why does the persistence context make the number of distinct related rows matter, not the number of parents?
- Why is a join fetch of a collection combined with paging dangerous?
