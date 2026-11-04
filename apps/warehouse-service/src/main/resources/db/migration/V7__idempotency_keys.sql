-- V7: results of stock writes sent with an Idempotency-Key (unit 8.1). The key is claimed with INSERT ... ON CONFLICT
-- DO NOTHING in the same transaction as the write, so a committed row always holds the final response and a failed write
-- leaves no row. fingerprint binds the key to the caller, the operation and the request; created_at drives the purge.

CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(80) PRIMARY KEY,
    fingerprint     CHAR(64)    NOT NULL,
    response_body   TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idempotency_keys_created_at_idx ON idempotency_keys (created_at);
