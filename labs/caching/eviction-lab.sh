#!/usr/bin/env bash
# Redis eviction lab (unit 6.2).
#
# Starts a throw-away, memory-capped Redis (service redis-lab of compose.labs.yaml) with the policy you pass, writes
# 50 "hot" keys that are read again and again plus many "cold" keys written once, and prints what Redis did.
#
#   labs/caching/eviction-lab.sh <policy> [maxmemory] [cold-keys] [ttl-seconds]
#
#   labs/caching/eviction-lab.sh allkeys-lru
#   labs/caching/eviction-lab.sh noeviction 8mb
#   labs/caching/eviction-lab.sh volatile-lru 8mb 12000 3600
#
# The cold keys go in batches of 1000, one second apart. Redis measures key age in whole seconds, so a run that
# finishes within a second cannot tell recent keys from old ones and LRU degenerates into a random choice.
#
# Run from the repository root. Stop the lab Redis afterwards with:
#   docker compose -f compose.yaml -f compose.labs.yaml --profile eviction rm -sf redis-lab
set -euo pipefail

policy="${1:?usage: eviction-lab.sh <policy> [maxmemory] [cold-keys] [ttl-seconds]}"
maxmemory="${2:-8mb}"
cold="${3:-12000}"
ttl="${4:-0}"

dc=(docker compose -f compose.yaml -f compose.labs.yaml --profile eviction)

LAB_REDIS_POLICY="$policy" LAB_REDIS_MAXMEMORY="$maxmemory" "${dc[@]}" up -d --force-recreate redis-lab >/dev/null
until [ "$("${dc[@]}" exec -T redis-lab redis-cli ping 2>/dev/null || true)" = "PONG" ]; do sleep 1; done

echo "policy=$policy maxmemory=$maxmemory cold_keys=$cold value_bytes=1024 ttl=$ttl"

"${dc[@]}" exec -T -e COLD="$cold" -e TTL="$ttl" redis-lab sh -s <<'EOS' | tr -d '\r'
HOT=50
SIZE=1024
BATCH=1000
: > /tmp/lab-out.txt

# writes: $1 = first cold key number, $2 = last; hot keys are created before batch 1 and read after every batch
gen() {
  awk -v from="$1" -v to="$2" -v size=$SIZE -v hot=$HOT -v ttl="$TTL" '
    function setc(k, v) { if (ttl > 0) print "SET " k " " v " EX " ttl; else print "SET " k " " v }
    BEGIN {
      v = ""; for (k = 0; k < size; k++) v = v "x"
      if (from == 1) for (h = 1; h <= hot; h++) setc("hot:" h, v)
      for (i = from; i <= to; i++) setc("cold:" i, v)
      for (h = 1; h <= hot; h++) print "GET hot:" h
    }'
}

from=1
while [ "$from" -le "$COLD" ]; do
  to=$((from + BATCH - 1)); [ "$to" -gt "$COLD" ] && to=$COLD
  gen "$from" "$to" | redis-cli >> /tmp/lab-out.txt
  sleep 1
  from=$((to + 1))
done

echo "cold_writes_refused_with_OOM=$(grep -c '^OOM' /tmp/lab-out.txt || true)"
alive=$(awk -v hot=$HOT 'BEGIN { for (h = 1; h <= hot; h++) print "EXISTS hot:" h }' | redis-cli | awk '{ s += $1 } END { print s + 0 }')
total=$(redis-cli dbsize)
echo "hot_keys_alive=$alive/$HOT"
echo "cold_keys_alive=$((total - alive))"
redis-cli info stats | grep -E '^evicted_keys'
redis-cli info memory | grep -E '^(used_memory_human|maxmemory_human|maxmemory_policy)'
EOS
