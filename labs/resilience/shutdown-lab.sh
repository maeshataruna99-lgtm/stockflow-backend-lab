#!/usr/bin/env bash
# Graceful shutdown lab (unit 8.5), against the running lab stack (the warehouse needs STOCKFLOW_LAB_UNSAFE_STOCK=true,
# which compose.labs.yaml sets).
#
#   docker compose -f compose.yaml -f compose.labs.yaml up -d
#   labs/resilience/shutdown-lab.sh <admin-token> [requests]
#
# Sends N requests that each keep a database connection busy for 6 s straight at the warehouse, then stops the container
# 1.5 s later: first with `docker stop` (SIGTERM, the container drains its requests) and then with `docker kill`
# (SIGKILL, nothing drains). Prints how many requests got a 200 and how long the stop took. Run from the repository root.
set -euo pipefail

token="${1:?usage: shutdown-lab.sh <admin-token> [requests]}"
requests="${2:-10}"
dc=(docker compose -f compose.yaml -f compose.labs.yaml)

wait_healthy() {
  for _ in $(seq 1 60); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' stockflow-warehouse 2>/dev/null)" = healthy ] && return 0
    sleep 3
  done
  echo "warehouse did not become healthy" >&2
  exit 1
}

run() { # $1 = label, $2 = stop command
  wait_healthy
  statuses=$(mktemp)
  "${dc[@]}" exec -T gateway sh -s > "$statuses" <<EOF &
for i in \$(seq 1 $requests); do
  ( wget -S -q -O /dev/null --header='Authorization: Bearer $token' "http://warehouse:8082/api/v1/stock/lab/hold?millis=6000" 2>&1 \
      | sed -n 's/.*HTTP\/1.1 \([0-9]*\).*/\1/p' | tail -1 | sed 's/^\$/failed/' ) &
done
wait
EOF
  loader=$!
  sleep 1.5
  started=$(date +%s)
  eval "$2" >/dev/null
  stopped=$(( $(date +%s) - started ))
  wait "$loader" || true
  ok=$(grep -c '^200$' "$statuses" || true)
  echo "$1: $requests requests in flight, answered 200: $ok, container stopped after ${stopped}s"
  rm -f "$statuses"
  docker start stockflow-warehouse >/dev/null
}

run "docker stop (SIGTERM, graceful)" "docker stop -t 30 stockflow-warehouse"
run "docker kill (SIGKILL)         " "docker kill stockflow-warehouse"
