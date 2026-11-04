-- V8: transactional outbox for stock events and idempotent consumer inbox (Phase 9).
-- A stock write inserts its stock_outbox row in the same database transaction as the stock change, so a committed write
-- always leaves an event to publish and a rolled-back write never leaves a phantom event. The relay claims unpublished
-- rows with FOR UPDATE SKIP LOCKED and marks published_at = now() after delivery; a consumer claims event_id in
-- processed_events with INSERT ... ON CONFLICT DO NOTHING in the same transaction as its side effect, so duplicate
-- delivery (at-least-once relay or webhook retry) is applied once.

CREATE TABLE stock_outbox (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id     VARCHAR(64) NOT NULL UNIQUE,
    aggregate_id BIGINT      NOT NULL,
    event_type   VARCHAR(64) NOT NULL,
    payload      TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX stock_outbox_unpublished_idx ON stock_outbox (id) WHERE published_at IS NULL;

CREATE TABLE processed_events (
    event_id     VARCHAR(64) PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
