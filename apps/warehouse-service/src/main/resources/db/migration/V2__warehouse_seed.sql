-- V2: deterministic demo seed for warehousedb. Flyway records this
-- version in flyway_schema_history, so a restart can never duplicate the
-- rows. Explicit IDs keep the seed deterministic; the identity sequences
-- are advanced to stay consistent for later Phase 3 inserts.

INSERT INTO warehouses (id, code, name, address, active) OVERRIDING SYSTEM VALUE VALUES
    (1, 'WH-JKT01', 'Jakarta Main Warehouse', 'Jl. Logistik Raya No. 1, Jakarta', TRUE);

INSERT INTO storage_locations (id, warehouse_id, code, active) OVERRIDING SYSTEM VALUE VALUES
    (1, 1, 'A-01-01', TRUE),
    (2, 1, 'A-01-02', TRUE),
    (3, 1, 'B-02-01', TRUE);

SELECT setval(pg_get_serial_sequence('warehouses', 'id'),        (SELECT MAX(id) FROM warehouses));
SELECT setval(pg_get_serial_sequence('storage_locations', 'id'), (SELECT MAX(id) FROM storage_locations));
