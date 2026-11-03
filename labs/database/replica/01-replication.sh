#!/bin/sh
# Primary side of the replica lab (unit 7.10). Runs once, while the primary is initialised: creates the role a
# standby uses to stream WAL, and allows that role to connect for replication. Lab use only.
set -e
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres \
  -c "CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD '$POSTGRES_PASSWORD'"
echo "host replication replicator all scram-sha-256" >> "$PGDATA/pg_hba.conf"
