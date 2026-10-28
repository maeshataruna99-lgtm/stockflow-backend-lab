#!/bin/bash
# Creates the two logical databases, one per service (ADR 0002), each owned
# by its least-privilege role. CONNECT is revoked from PUBLIC so the other
# service's role cannot even open a connection - Phase 2 verifies this
# against the real databases.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-'EOSQL'
CREATE DATABASE catalogdb OWNER catalog_app;
CREATE DATABASE warehousedb OWNER warehouse_app;
REVOKE ALL ON DATABASE catalogdb FROM PUBLIC;
REVOKE ALL ON DATABASE warehousedb FROM PUBLIC;
EOSQL
