-- V2: deterministic demo seed for catalogdb. Flyway records this version
-- in flyway_schema_history, so a restart can never duplicate the rows.
-- Explicit IDs keep the seed deterministic; the identity sequences are
-- advanced to stay consistent for later Phase 3 inserts.

INSERT INTO categories (id, code, name, active) OVERRIDING SYSTEM VALUE VALUES
    (1, 'ELECTRONICS',  'Electronics',    TRUE),
    (2, 'HOME-KITCHEN', 'Home & Kitchen', TRUE);

INSERT INTO units (id, code, name) OVERRIDING SYSTEM VALUE VALUES
    (1, 'PCS', 'Pieces'),
    (2, 'KG',  'Kilogram');

INSERT INTO products (id, sku, name, description, category_id, unit_id, active) OVERRIDING SYSTEM VALUE VALUES
    (1, 'SPK-X1',  'Bluetooth Speaker X1', 'Compact portable speaker', 1, 1, TRUE),
    (2, 'DSK-MAT', 'Standing Desk Mat',    'Anti-fatigue desk mat',    2, 1, TRUE);

INSERT INTO skus (id, product_id, code, barcode, active) OVERRIDING SYSTEM VALUE VALUES
    (1, 1, 'SPK-X1-BLK', '8996000000011', TRUE),
    (2, 2, 'DSK-MAT-60', '8996000000028', TRUE);

SELECT setval(pg_get_serial_sequence('categories', 'id'), (SELECT MAX(id) FROM categories));
SELECT setval(pg_get_serial_sequence('units', 'id'),      (SELECT MAX(id) FROM units));
SELECT setval(pg_get_serial_sequence('products', 'id'),   (SELECT MAX(id) FROM products));
SELECT setval(pg_get_serial_sequence('skus', 'id'),       (SELECT MAX(id) FROM skus));
