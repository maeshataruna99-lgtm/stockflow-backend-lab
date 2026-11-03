-- V5 (expand): warehouses.address is being renamed to street_address in three releases (unit 7.9, guidebook 23).
-- This step only adds. The release that still reads and writes `address` keeps working, and so does the release that
-- uses `street_address`: a trigger copies a change of either column into the other. Nothing is dropped or locked
-- for long (ADD COLUMN without a default is a catalog change). A table this small is backfilled in one statement; on a
-- large table backfill in batches of a few thousand rows, committing between them.

ALTER TABLE warehouses ADD COLUMN street_address TEXT;

CREATE FUNCTION warehouses_sync_address() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.street_address IS NULL THEN
            NEW.street_address := NEW.address;
        ELSE
            NEW.address := NEW.street_address;   -- the new column wins when both are given
        END IF;
    ELSIF NEW.street_address IS DISTINCT FROM OLD.street_address THEN
        NEW.address := NEW.street_address;       -- the new column changed (alone or together with the old one)
    ELSIF NEW.address IS DISTINCT FROM OLD.address THEN
        NEW.street_address := NEW.address;       -- only the old column changed
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER warehouses_sync_address BEFORE INSERT OR UPDATE ON warehouses
    FOR EACH ROW EXECUTE FUNCTION warehouses_sync_address();

UPDATE warehouses SET street_address = address WHERE street_address IS NULL AND address IS NOT NULL;
