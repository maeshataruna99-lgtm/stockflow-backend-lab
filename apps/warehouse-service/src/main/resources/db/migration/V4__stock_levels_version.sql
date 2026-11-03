-- V4: row version for the optimistic locking lab (unit 7.2). Every writer that changes quantity adds 1,
-- so a writer that read an older version can tell the row changed in between.

ALTER TABLE stock_levels ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
