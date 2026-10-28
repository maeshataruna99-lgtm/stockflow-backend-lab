-- V1: baseline schema for warehousedb (ADR 0002: warehouse-service owns
-- warehouses, storage_locations). Tables live in the public schema,
-- created and owned by warehouse_app. No cross-database foreign keys;
-- product references arrive as IDs over HTTP APIs in Phase 3+.

CREATE TABLE warehouses (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code       VARCHAR(32)  NOT NULL UNIQUE,
    name       VARCHAR(160) NOT NULL,
    address    TEXT,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE storage_locations (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    warehouse_id BIGINT       NOT NULL REFERENCES warehouses (id),
    code         VARCHAR(64)  NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (warehouse_id, code)
);
