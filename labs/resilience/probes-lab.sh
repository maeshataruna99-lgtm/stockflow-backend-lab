#!/usr/bin/env bash
# Liveness and readiness lab (unit 8.4), against the running stack. Freezes the PostgreSQL container (docker pause: the
# process stays, connections hang) and asks the warehouse and the catalog for both probes, then unfreezes it.
#
#   docker compose -f compose.yaml -f compose.labs.yaml up -d
#   labs/resilience/probes-lab.sh
#
# Expected: with the database frozen readiness is 503 and liveness stays 200, so an orchestrator would stop sending
# traffic but would not restart the service; after the unfreeze readiness returns to 200. Run from the repository root.
set -euo pipefail

dc=(docker compose -f compose.yaml -f compose.labs.yaml)
probe() { # $1 = service, $2 = port, $3 = liveness|readiness ; prints the HTTP status and the time it took in ms
  "${dc[@]}" exec -T "$1" sh -c "
    s=\$(date +%s%N 2>/dev/null || date +%s)
    code=\$(wget -S -q -O /dev/null http://localhost:$2/actuator/health/$3 2>&1 | sed -n 's/.*HTTP\/1.1 \([0-9]*\).*/\1/p' | tail -1)
    e=\$(date +%s%N 2>/dev/null || date +%s)
    echo \"\${code:-none}\"" 2>/dev/null | tr -d '\r'
}
show() {
  echo "$1: warehouse liveness=$(probe warehouse 8082 liveness) readiness=$(probe warehouse 8082 readiness)" \
       "| catalog liveness=$(probe catalog 8081 liveness) readiness=$(probe catalog 8081 readiness)"
}

show "database up      "
docker pause stockflow-postgres >/dev/null
trap 'docker unpause stockflow-postgres >/dev/null 2>&1 || true' EXIT
sleep 3
show "database frozen  "
docker unpause stockflow-postgres >/dev/null
trap - EXIT
for _ in $(seq 1 12); do
  if [ "$(probe warehouse 8082 readiness)" = 200 ] && [ "$(probe catalog 8081 readiness)" = 200 ]; then break; fi
  sleep 5
done
show "database unfrozen"
