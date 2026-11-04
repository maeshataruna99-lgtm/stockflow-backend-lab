#!/usr/bin/env bash
# Idempotency key lab (unit 8.1), against the running stack. Creates a stock row of 10 units, then
#  1. sends the same withdrawal of 3 with the same Idempotency-Key three times, one after the other,
#  2. sends it 20 times at the same moment with one key,
#  3. sends the same key with a different quantity,
#  4. sends a withdrawal without a key twice.
# It prints the status codes and the units left. Straight at the warehouse from inside the Docker network.
#
#   docker compose -f compose.yaml -f compose.labs.yaml up -d
#   labs/resilience/idempotency-lab.sh <admin-token>
#
# Run from the repository root.
set -euo pipefail

token="${1:?usage: idempotency-lab.sh <admin-token>}"
dc=(docker compose -f compose.yaml -f compose.labs.yaml)
base="http://warehouse:8082/api/v1/stock"

call() { # $1 = path, $2 = data (optional), $3 = idempotency key (optional)
  "${dc[@]}" exec -T gateway wget -qO- --header='Content-Type: application/json' --header="Authorization: Bearer $token" \
    ${3:+--header="Idempotency-Key: $3"} ${2:+--post-data="$2"} "$base$1"
}
status() { # same call, prints only the HTTP status
  "${dc[@]}" exec -T gateway sh -c "wget -S -q -O /dev/null --header='Content-Type: application/json' --header='Authorization: Bearer $token' ${3:+--header='Idempotency-Key: $3'} --post-data='$2' '$base$1' 2>&1 | sed -n 's/.*HTTP\/1.1 \([0-9]*\).*/\1/p' | tail -1" | tr -d '\r'
}
field() { sed -n "s/.*\"$1\":\([0-9]*\).*/\1/p" | head -1; }
new_stock() { call "" "{\"locationId\":1,\"productId\":$(date +%s%N),\"quantity\":10}" | field id; }
left() { call "/$1" | field quantity; }

id=$(new_stock); key="lab-$(date +%s%N)"
echo "1. same key, same request, three times: statuses $(status "/$id/withdraw" '{"quantity":3}' "$key") $(status "/$id/withdraw" '{"quantity":3}' "$key") $(status "/$id/withdraw" '{"quantity":3}' "$key"), units left $(left "$id") (10 - 3 = 7)"

id=$(new_stock); key="lab-$(date +%s%N)"
codes=$("${dc[@]}" exec -T gateway sh -s <<EOF
for i in \$(seq 1 20); do
  ( wget -S -q -O /dev/null --header='Content-Type: application/json' --header='Authorization: Bearer $token' --header='Idempotency-Key: $key' \
      --post-data='{"quantity":3}' "$base/$id/withdraw" 2>&1 | sed -n 's/.*HTTP\/1.1 \([0-9]*\).*/\1/p' | tail -1 ) &
done
wait
EOF
)
echo "2. 20 simultaneous requests, one key: $(echo "$codes" | grep -c '^200') x 200, $(echo "$codes" | grep -vc '^200' || true) other, units left $(left "$id") (7)"

id=$(new_stock); key="lab-$(date +%s%N)"
status "/$id/withdraw" '{"quantity":3}' "$key" >/dev/null
echo "3. same key, quantity 4 instead of 3: status $(status "/$id/withdraw" '{"quantity":4}' "$key") (422), units left $(left "$id") (7)"

id=$(new_stock)
echo "4. no key, two requests: statuses $(status "/$id/withdraw" '{"quantity":3}') $(status "/$id/withdraw" '{"quantity":3}'), units left $(left "$id") (4)"
