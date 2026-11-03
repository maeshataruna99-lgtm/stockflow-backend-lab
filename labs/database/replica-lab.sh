#!/usr/bin/env bash
# Read replica lab (unit 7.10), against the compose profile "replica": a PostgreSQL primary (pg-primary) and a
# streaming-replication standby of it (pg-replica), separate from the service databases.
#
#   docker compose -f compose.yaml -f compose.labs.yaml --profile replica up -d
#   labs/database/replica-lab.sh
#
# Shows that the standby streams and is read-only, how long a committed row takes to appear on it, and what a read
# from a standby that is behind returns (it is paused with pg_wal_replay_pause, then resumed). Run from the repository
# root. The password for the lab roles comes from POSTGRES_SUPER_PASSWORD in .env, through compose only.
#
# Timings: the writes and the polling run inside ONE docker exec in the primary container, which reaches the standby
# over the Docker network with psql. Starting a docker exec costs about a second, so timing across execs would measure
# that, not replication. Both containers share the host clock. Resolution is about the cost of one psql call.
set -euo pipefail

dc=(docker compose -f compose.yaml -f compose.labs.yaml --profile replica)
primary() { "${dc[@]}" exec -T pg-primary psql -U postgres -d postgres -At -v ON_ERROR_STOP=1 "$@"; }
replica() { "${dc[@]}" exec -T pg-replica psql -U postgres -d postgres -At "$@"; }
inside_primary() { "${dc[@]}" exec -T pg-primary sh -c 'export PGPASSWORD="$POSTGRES_PASSWORD"; '"$1"; }

echo "== 1. the standby streams from the primary"
echo "primary in recovery: $(primary -c 'select pg_is_in_recovery()')   replica in recovery: $(replica -c 'select pg_is_in_recovery()')"
echo "pg_stat_replication: $(primary -c "select application_name||' '||state||' sync_state='||sync_state from pg_stat_replication")"

echo "== 2. the standby is read-only"
primary -c "drop table if exists lab_lag" -c "create table lab_lag (id bigserial primary key, marker text not null)" >/dev/null 2>&1
replica -c "insert into lab_lag (marker) values ('on the replica')" 2>&1 | sed 's/^/replica insert: /' || true

echo "== 3. time until a committed row is visible on the standby"
inside_primary '
ms="select (extract(epoch from clock_timestamp())*1000)::bigint"
for i in 1 2 3 4 5; do
  committed=$(psql -U postgres -At -q -c "insert into lab_lag (marker) values ('"'"'visible-$i'"'"')" -c "$ms" | tail -1)
  until [ "$(psql -h pg-replica -U postgres -At -c "select count(*) from lab_lag where marker = '"'"'visible-$i'"'"'" 2>/dev/null)" = 1 ]; do :; done
  seen=$(psql -h pg-replica -U postgres -At -c "$ms")
  echo "row $i visible on the replica $((seen - committed)) ms after the commit"
done'

echo "== 4. a standby that is behind returns stale reads"
replica -c "select pg_wal_replay_pause()" >/dev/null
sleep 1
primary -c "insert into lab_lag (marker) values ('written while behind')" >/dev/null
lsn=$(primary -c "select pg_current_wal_lsn()")
echo "primary has the row: $(primary -c "select count(*) from lab_lag where marker='written while behind'")   replica returns: $(replica -c "select count(*) from lab_lag where marker='written while behind'")"
echo "replay lag in bytes (primary view): $(primary -c "select pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn)::bigint from pg_stat_replication")"
echo "replica reached the write's LSN $lsn: $(replica -c "select pg_last_wal_replay_lsn() >= '$lsn'::pg_lsn")  (false: read the primary instead)"
inside_primary '
ms="select (extract(epoch from clock_timestamp())*1000)::bigint"
before=$(psql -U postgres -At -c "$ms")
psql -h pg-replica -U postgres -At -c "select pg_wal_replay_resume()" >/dev/null
until [ "$(psql -h pg-replica -U postgres -At -c "select pg_last_wal_replay_lsn() >= '"'$lsn'"'::pg_lsn" 2>/dev/null)" = t ]; do :; done
seen=$(psql -h pg-replica -U postgres -At -c "$ms")
echo "after the resume the replica reached the LSN within $((seen - before)) ms"' 2>&1 || true
echo "the replica now returns: $(replica -c "select count(*) from lab_lag where marker='written while behind'")"
