#!/usr/bin/env bash
# Deadlock lab (unit 7.3), against the running lab stack (the warehouse needs STOCKFLOW_LAB_UNSAFE_STOCK=true,
# which compose.labs.yaml sets).
#
#   docker compose -f compose.yaml -f compose.labs.yaml up --build -d
#   labs/database/deadlock-lab.sh <admin-token> [rounds]
#
# Each round creates two stock rows and sends two opposite transfers at the same moment, first to the endpoint that
# locks "from" then "to" (with a 500 ms pause between the locks), then to the endpoint that locks in id order.
# Prints the HTTP status of each transfer, the final quantities and how many "deadlock detected" lines PostgreSQL
# logged. Expected: unordered = one 200 and one 409, ordered = two 200. Run from the repository root.
set -euo pipefail

token="${1:?usage: deadlock-lab.sh <admin-token> [rounds]}"
rounds="${2:-3}"
dc=(docker compose -f compose.yaml -f compose.labs.yaml)
base="http://warehouse:8082/api/v1/stock"

call() { # $1 = path, $2 = post data (optional)
  "${dc[@]}" exec -T gateway wget -qO- --header='Content-Type: application/json' \
    --header="Authorization: Bearer $token" ${2:+--post-data="$2"} "$base$1"
}
json_field() { sed -n "s/.*\"$1\":\([0-9]*\).*/\1/p" | head -1; }
deadlocks() { { "${dc[@]}" logs postgres 2>&1 || true; } | grep -c "deadlock detected" || true; }

transfer_pair() { # $1 = endpoint path, $2 = row a, $3 = row b; prints "forward=<status> backward=<status>"
  "${dc[@]}" exec -T gateway sh -s <<EOF
post() { wget -S -q -O /dev/null --header='Content-Type: application/json' --header='Authorization: Bearer $token' \
  --post-data="\$2" "$base\$1" 2>&1 | sed -n 's/.*HTTP\/1.1 \([0-9]*\).*/\1/p' | tail -1; }
( echo "forward=\$(post '$1' '{"fromId":$2,"toId":$3,"quantity":1}')" ) &
( echo "backward=\$(post '$1' '{"fromId":$3,"toId":$2,"quantity":1}')" ) &
wait
EOF
}

for round in $(seq 1 "$rounds"); do
  for mode in transfer-unordered?pauseMillis=500 transfer; do
    product=$(date +%s%N)
    a=$(call "" "{\"locationId\":1,\"productId\":$product,\"quantity\":10}" | json_field id)
    b=$(call "" "{\"locationId\":2,\"productId\":$product,\"quantity\":10}" | json_field id)
    before=$(deadlocks)
    result=$(transfer_pair "/$mode" "$a" "$b" | sort | tr '\r\n' '  ')
    echo "round $round ${mode%%\?*}: $result a=$(call "/$a" | json_field quantity) b=$(call "/$b" | json_field quantity)" \
      "deadlocks_logged=+$(( $(deadlocks) - before ))"
  done
done
