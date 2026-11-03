#!/usr/bin/env bash
# Constraint lab (unit 7.8), against the running lab stack. Sends N requests at the same moment that all create the
# same warehouse code, straight at the warehouse from inside the Docker network, and prints how many got 201, how
# many 409, anything else, and how many warehouses with that code exist afterwards. Expected: 1, N-1, 0, 1.
#
#   docker compose -f compose.yaml -f compose.labs.yaml up --build -d
#   labs/database/constraint-lab.sh <admin-token> [concurrent-requests]
#
# Run from the repository root.
set -euo pipefail

token="${1:?usage: constraint-lab.sh <admin-token> [concurrent-requests]}"
requests="${2:-20}"
dc=(docker compose -f compose.yaml -f compose.labs.yaml)
code="LAB-$(date +%s)"

statuses=$("${dc[@]}" exec -T gateway sh -s <<INNER
for i in \$(seq 1 $requests); do
  ( wget -S -q -O /dev/null --header='Content-Type: application/json' --header='Authorization: Bearer $token' \
      --post-data='{"code":"$code","name":"constraint lab","address":"x"}' http://warehouse:8082/api/v1/warehouses 2>&1 \
      | sed -n 's/.*HTTP\/1.1 \([0-9]*\).*/\1/p' | tail -1 ) &
done
wait
INNER
)
rows=$("${dc[@]}" exec -T gateway wget -qO- --header="Authorization: Bearer $token" \
  "http://warehouse:8082/api/v1/warehouses?size=100" | grep -o "\"code\":\"$code\"" | wc -l)
other=$(echo "$statuses" | grep -vc -e '^201$' -e '^409$' || true)
echo "code=$code requests=$requests: 201=$(echo "$statuses" | grep -c '^201$') 409=$(echo "$statuses" | grep -c '^409$') other=$other rows_with_that_code=$rows"
