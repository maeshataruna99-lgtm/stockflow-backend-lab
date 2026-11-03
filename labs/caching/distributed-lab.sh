#!/usr/bin/env bash
# Distributed caching lab (unit 6.4), against the running lab stack with two catalog instances.
#
#   docker compose -f compose.yaml -f compose.labs.yaml up --build -d
#   labs/caching/distributed-lab.sh <admin-token> [herd-requests]
#
# 1. A product read on `catalog` fills the shared Redis key; the same read on `catalog-2` is a hit, not a load.
# 2. A PATCH through the gateway (it lands on either instance) removes the key; both instances then return the
#    new description and the first of them loads it once.
# 3. A herd of identical reads split over both instances while the key is missing: loads are counted per instance.
# Reads go straight to each instance from inside the Docker network, so the gateway rate limit is not in the way.
# Run from the repository root.
set -euo pipefail

token="${1:?usage: distributed-lab.sh <admin-token> [herd-requests]}"
herd="${2:-40}"
dc=(docker compose -f compose.yaml -f compose.labs.yaml)
key="catalog:product:v1:1"

metric() { # $1 = instance, $2 = result tag
  "${dc[@]}" exec -T "$1" wget -qO- "--header=Authorization: Bearer $token" \
    "http://localhost:8081/actuator/metrics/catalog.cache?tag=result:$2" 2>/dev/null \
    | sed -n 's/.*"statistic":"COUNT","value":\([0-9.]*\).*/\1/p' | head -1 | cut -d. -f1 || true
}
count() { local v; v=$(metric "$1" "$2"); echo "${v:-0}"; }

read_description() { # $1 = instance
  "${dc[@]}" exec -T gateway wget -qO- "--header=Authorization: Bearer $token" "http://$1:8081/api/v1/products/1" \
    | sed -n 's/.*"description":"\([^"]*\)".*/\1/p'
}

echo "== 1. one instance fills the cache, the other hits it"
"${dc[@]}" exec -T redis redis-cli DEL "$key" >/dev/null
a_load=$(count catalog load); b_load=$(count catalog-2 load); b_hit=$(count catalog-2 hit)
read_description catalog >/dev/null
read_description catalog-2 >/dev/null
echo "catalog   loads +$(( $(count catalog load) - a_load ))   (expected 1)"
echo "catalog-2 loads +$(( $(count catalog-2 load) - b_load )), hits +$(( $(count catalog-2 hit) - b_hit ))   (expected 0 and 1)"
echo "redis key exists: $("${dc[@]}" exec -T redis redis-cli EXISTS "$key" | tr -d '\r')   (expected 1)"

echo "== 2. an update through the gateway evicts the shared key for both instances"
new="distributed lab $(date +%s)"
curl -s -o /dev/null -w "PATCH status %{http_code}\n" -X PATCH http://localhost:8080/api/v1/products/1 \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d "{\"description\":\"$new\"}"
echo "redis key exists after PATCH: $("${dc[@]}" exec -T redis redis-cli EXISTS "$key" | tr -d '\r')   (expected 0)"
a_load=$(count catalog load); b_load=$(count catalog-2 load)
echo "catalog-2 returns: $(read_description catalog-2)"
echo "catalog   returns: $(read_description catalog)"
echo "expected both: $new"
echo "loads after the update: catalog +$(( $(count catalog load) - a_load )), catalog-2 +$(( $(count catalog-2 load) - b_load ))   (expected 1 in total)"

echo "== 3. a herd of $herd reads split over both instances, key missing"
"${dc[@]}" exec -T redis redis-cli DEL "$key" >/dev/null
a_load=$(count catalog load); b_load=$(count catalog-2 load)
half=$(( herd / 2 ))
ok=$("${dc[@]}" exec -T gateway sh -c "
  for i in \$(seq 1 $half); do
    ( wget -q -O /dev/null --header='Authorization: Bearer $token' http://catalog:8081/api/v1/products/1 && echo ok ) &
    ( wget -q -O /dev/null --header='Authorization: Bearer $token' http://catalog-2:8081/api/v1/products/1 && echo ok ) &
  done
  wait" | grep -c '^ok$' || true)
sleep 1
echo "requests=$(( half * 2 )) ok=$ok database_loads: catalog +$(( $(count catalog load) - a_load )), catalog-2 +$(( $(count catalog-2 load) - b_load ))   (at most 1 each, 2 in total)"
