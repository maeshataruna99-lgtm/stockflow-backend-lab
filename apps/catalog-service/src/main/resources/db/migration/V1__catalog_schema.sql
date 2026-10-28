-- V1: baseline schema for catalogdb (ADR 0002: catalog-service owns
-- products, skus, categories, units). Tables live in the public schema,
-- created and owned by catalog_app. Cross-service references are IDs
-- exchanged over HTTP APIs, never foreign keys into another database.

CREATE TABLE categories (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code       VARCHAR(40)  NOT NULL UNIQUE,
    name       VARCHAR(120) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE units (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code       VARCHAR(16)  NOT NULL UNIQUE,
    name       VARCHAR(64)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE products (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sku         VARCHAR(32)  NOT NULL UNIQUE,
    name        VARCHAR(160) NOT NULL,
    description TEXT,
    category_id BIGINT       NOT NULL REFERENCES categories (id),
    unit_id     BIGINT       NOT NULL REFERENCES units (id),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE skus (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id BIGINT       NOT NULL REFERENCES products (id),
    code       VARCHAR(32)  NOT NULL UNIQUE,
    barcode    VARCHAR(64),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
