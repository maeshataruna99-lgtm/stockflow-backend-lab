#!/usr/bin/env bash
# Race condition lab (unit 7.1), against the running lab stack (the warehouse needs STOCKFLOW_LAB_UNSAFE_STOCK=true,
# which compose.labs.yaml sets).
#
#   docker compose -f compose.yaml -f compose.labs.yaml up --build -d
#   labs/database/race-lab.sh <admin-token> [concurrent-requests] [starting-stock]
#
# For each of the two endpoints it creates a stock row, fires N requests at the same moment that each withdraw one
# unit (straight at the warehouse from inside the Docker network), and prints how many were accepted and how many
# units are left. Correct: accepted + left = starting stock, and accepted never exceeds the stock.
# Run from the repository root.
set -euo pipefail

token="${1:?usage: race-lab.sh <admin-token> [concurrent-requests] [starting-stock]}"
requests="${2:-100}"
stock="${3:-100}"
dc=(docker compose -f compose.yaml -f compose.labs.yaml)
base="http://warehouse:8082/api/v1/stock"

call() { # $1 = path, $2 = method data (optional)
  "${dc[@]}" exec -T gateway wget -qO- --header='Content-Type: application/json' \
    --header="Authorization: Bearer $token" ${2:+--post-data="$2"} "$base$1"
}
json_field() { sed -n "s/.*\"$1\":\([0-9]*\).*/\1/p"; }

for endpoint in withdraw-unsafe withdraw; do
  id=$(call "" "{\"locationId\":1,\"productId\":$(date +%s%N),\"quantity\":$stock}" | json_field id)
  ok=$("${dc[@]}" exec -T gateway sh -c "
    for i in \$(seq 1 $requests); do
      ( wget -q -O /dev/null --header='Content-Type: application/json' --header='Authorization: Bearer $token' \
          --post-data='{\"quantity\":1}' $base/$id/$endpoint 2>/dev/null && echo ok ) &
    done
    wait" | grep -c '^ok$' || true)
  left=$(call "/$id" | json_field quantity)
  echo "$endpoint: stock=$stock requests=$requests accepted=$ok left=$left accepted+left=$((ok + left)) (correct: $stock)"
done
