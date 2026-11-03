-- V3: indexes for GET /api/v1/products (unit 7.7, plans and timings in docs/STATUS.md and guidebook 21).
-- category_id has no index from the foreign key; the count query of every page and the list of a rare category
-- scan the whole table without it. The trigram indexes serve the contains-search lower(name) / lower(sku)
-- LIKE '%term%', which no btree can use. pg_trgm is a trusted extension, the database owner may create it.
-- Plain CREATE INDEX locks writes while it builds; on a large live table use CREATE INDEX CONCURRENTLY (unit 7.9).

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX products_category_id_idx ON products (category_id);
CREATE INDEX products_name_trgm_idx ON products USING gin (lower(name) gin_trgm_ops);
CREATE INDEX products_sku_trgm_idx ON products USING gin (lower(sku) gin_trgm_ops);
