-- V3: stock per storage location and product (Phase 7 database labs). product_id is a plain
-- number, not a foreign key: the product lives in catalogdb (ADR 0002, no cross-database
-- foreign keys). The CHECK keeps the quantity from going negative, but it cannot see a lost update.

CREATE TABLE stock_levels (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    location_id BIGINT      NOT NULL REFERENCES storage_locations (id),
    product_id  BIGINT      NOT NULL,
    quantity    INTEGER     NOT NULL CHECK (quantity >= 0),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (location_id, product_id)
);
