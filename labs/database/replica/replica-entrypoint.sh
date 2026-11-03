#!/bin/sh
# Standby side of the replica lab (unit 7.10): copies the primary with pg_basebackup on first start, then runs
# PostgreSQL as a hot standby that streams WAL from PRIMARY_HOST. Needs PRIMARY_HOST and REPLICATION_PASSWORD.
set -e
PGDATA="${PGDATA:-/var/lib/postgresql/data}"
until pg_isready -h "$PRIMARY_HOST" -U postgres >/dev/null 2>&1; do sleep 1; done
if [ ! -s "$PGDATA/PG_VERSION" ]; then
  mkdir -p "$PGDATA"
  chown postgres:postgres "$PGDATA"
  chmod 700 "$PGDATA"
  su-exec postgres env PGPASSWORD="$REPLICATION_PASSWORD" \
    pg_basebackup -h "$PRIMARY_HOST" -U replicator -D "$PGDATA" -R -X stream
  # -R writes standby.signal and a primary_conninfo without the password; the last line of the file wins
  echo "primary_conninfo = 'host=$PRIMARY_HOST user=replicator password=$REPLICATION_PASSWORD application_name=lab_replica'" \
    >> "$PGDATA/postgresql.auto.conf"
fi
exec docker-entrypoint.sh postgres -c hot_standby=on
