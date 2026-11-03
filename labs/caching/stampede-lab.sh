#!/usr/bin/env bash
# Cache stampede lab (unit 6.3), against the running lab stack.
#
#   docker compose -f compose.yaml -f compose.labs.yaml up --build -d          # single-flight on
#   LAB_SINGLE_FLIGHT=false docker compose -f compose.yaml -f compose.labs.yaml up -d catalog   # single-flight off
#   labs/caching/stampede-lab.sh <admin-token> [concurrent-requests] [rounds]
#
# Each round empties the cache entry of product 1, fires N identical GET requests at the same moment straight at
# the catalog service (from inside the Docker network, so the gateway rate limit is not in the way) and prints how
# many database loads and how many coalesced answers the catalog counted. Run from the repository root.
set -euo pipefail

token="${1:?usage: stampede-lab.sh <admin-token> [concurrent-requests] [rounds]}"
requests="${2:-40}"
rounds="${3:-3}"
dc=(docker compose -f compose.yaml -f compose.labs.yaml)

metric() { # $1 = result tag
  "${dc[@]}" exec -T catalog wget -qO- "--header=Authorization: Bearer $token" \
    "http://localhost:8081/actuator/metrics/catalog.cache?tag=result:$1" 2>/dev/null \
    | sed -n 's/.*"statistic":"COUNT","value":\([0-9.]*\).*/\1/p' | head -1 | cut -d. -f1 || true
}

for round in $(seq 1 "$rounds"); do
  "${dc[@]}" exec -T redis redis-cli DEL catalog:product:v1:1 >/dev/null
  loads_before=$(metric load); loads_before=${loads_before:-0}
  coalesced_before=$(metric coalesced); coalesced_before=${coalesced_before:-0}
  ok=$("${dc[@]}" exec -T gateway sh -c "
    for i in \$(seq 1 $requests); do
      ( wget -q -O /dev/null --header='Authorization: Bearer $token' http://catalog:8081/api/v1/products/1 && echo ok ) &
    done
    wait" | grep -c '^ok$' || true)
  sleep 1
  loads_after=$(metric load); loads_after=${loads_after:-0}; coalesced_after=$(metric coalesced); coalesced_after=${coalesced_after:-0}
  echo "round $round: requests=$requests ok=$ok database_loads=$((loads_after - loads_before)) coalesced=$((coalesced_after - coalesced_before))"
done
