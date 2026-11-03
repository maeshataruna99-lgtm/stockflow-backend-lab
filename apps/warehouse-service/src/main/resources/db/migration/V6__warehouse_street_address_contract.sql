-- V6 (contract): remove the old column. Deploy this only after the last instance of the release that reads or
-- writes `address` is gone (unit 7.9, guidebook 23); that release's SQL fails with "column does not exist" afterwards.
-- In this single-environment lab V5, the code switch and V6 are applied by one deploy; the staged order is proven by
-- ZeroDowntimeMigrationTest.

DROP TRIGGER warehouses_sync_address ON warehouses;
DROP FUNCTION warehouses_sync_address();
ALTER TABLE warehouses DROP COLUMN address;
