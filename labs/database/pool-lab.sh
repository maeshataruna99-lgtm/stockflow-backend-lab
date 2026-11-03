#!/usr/bin/env bash
# Connection pool lab (unit 7.5), against the running lab stack (the warehouse needs STOCKFLOW_LAB_UNSAFE_STOCK=true,
# which compose.labs.yaml sets). The warehouse pool is 10 connections with a 3 s wait for a free one.
#
#   docker compose -f compose.yaml -f compose.labs.yaml up --build -d
#   labs/database/pool-lab.sh <admin-token> [concurrent-requests] [hold-millis]
#
# Fires N requests at the same moment straight at the warehouse from inside the Docker network; each one keeps a
# database connection busy for hold-millis. Prints how many got 200 and how many got 503, and the wall time.
# Run from the repository root.
set -euo pipefail

token="${1:?usage: pool-lab.sh <admin-token> [concurrent-requests] [hold-millis]}"
requests="${2:-40}"
hold="${3:-1000}"
dc=(docker compose -f compose.yaml -f compose.labs.yaml)

start=$(date +%s)
statuses=$("${dc[@]}" exec -T gateway sh -s <<EOF
for i in \$(seq 1 $requests); do
  ( wget -S -q -O /dev/null --header='Authorization: Bearer $token' \
      "http://warehouse:8082/api/v1/stock/lab/hold?millis=$hold" 2>&1 \
      | sed -n 's/.*HTTP\/1.1 \([0-9]*\).*/\1/p' | tail -1 ) &
done
wait
EOF
)
end=$(date +%s)
echo "requests=$requests hold=${hold}ms pool=10 wait=3s: 200=$(echo "$statuses" | grep -c '^200') 503=$(echo "$statuses" | grep -c '^503') wall=$((end - start))s"
