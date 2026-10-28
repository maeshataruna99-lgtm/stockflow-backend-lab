#!/bin/bash
# Creates the two least-privilege application roles (ADR 0002).
# Runs only on the first init of an empty data volume, as the superuser
# (docker-entrypoint-initdb.d contract). Passwords come from the compose
# environment, which gets them from .env; the guards fail loudly if a
# password is missing so no default password can ever exist.
set -euo pipefail

: "${CATALOG_DB_PASSWORD:?CATALOG_DB_PASSWORD must be set in the container environment}"
: "${WAREHOUSE_DB_PASSWORD:?WAREHOUSE_DB_PASSWORD must be set in the container environment}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v catpwd="$CATALOG_DB_PASSWORD" -v whpwd="$WAREHOUSE_DB_PASSWORD" <<-'EOSQL'
CREATE ROLE catalog_app LOGIN PASSWORD :'catpwd';
CREATE ROLE warehouse_app LOGIN PASSWORD :'whpwd';
EOSQL
