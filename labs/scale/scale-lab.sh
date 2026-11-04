#!/usr/bin/env bash
# Scale lab (unit 8.6): the same load against 1, 2 and 3 catalog instances with a fixed CPU limit each (horizontal scaling),
# and against 1 instance with the CPU of the three (vertical scaling).
#
#   docker compose -f compose.yaml -f compose.labs.yaml -f compose.scale.yaml up -d --build postgres redis discovery gateway catalog
#   labs/scale/scale-lab.sh <admin-token> [measure-seconds] [concurrency]
#
# Load: CONCURRENCY workers inside the gateway container each loop GET /api/v1/products?size=50, going round the instances
# of the scenario (what a round-robin balancer does), straight at the services so the gateway rate limiter does not cap
# the result. A 10 s warm-up runs first for the JIT. The result is requests answered with 200 per second over the
# measured interval, plus the CPU and memory docker reports for each instance in the middle of the run.
# Run from the repository root. At the end the catalog is recreated without limits.
set -euo pipefail

token="${1:?usage: scale-lab.sh <admin-token> [measure-seconds] [concurrency]}"
measure="${2:-20}"
concurrency="${3:-24}"
cpus_each="${LAB_SCALE_BASE_CPUS:-0.5}"
dc=(docker compose -f compose.yaml -f compose.labs.yaml -f compose.scale.yaml)
all_instances=(catalog catalog-2 catalog-3)

wait_healthy() { # $@ = services
  for svc in "$@"; do
    id=$("${dc[@]}" ps -q "$svc")
    for _ in $(seq 1 60); do
      [ "$(docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null)" = healthy ] && continue 2
      sleep 5
    done
    echo "$svc did not become healthy" >&2
    exit 1
  done
}

load() { # $1 = seconds, $2... = target urls; prints "<ok> <errors> <elapsed seconds>"
  local seconds="$1"; shift
  "${dc[@]}" exec -T gateway sh -s <<EOF
targets="$*"
start=\$(date +%s); end=\$((start + $seconds))
for w in \$(seq 1 $concurrency); do
  (
    i=\$w
    while [ \$(date +%s) -lt \$end ]; do
      set -- \$targets
      eval url=\\\${\$((i % \$# + 1))}
      if wget -q -O /dev/null --header='Authorization: Bearer $token' "\$url"; then echo ok; else echo err; fi
      i=\$((i + 1))
    done
  ) &
done > /tmp/scale-load.txt
wait
stop=\$(date +%s)
echo "\$(grep -c '^ok\$' /tmp/scale-load.txt) \$(grep -c '^err\$' /tmp/scale-load.txt) \$((stop - start))"
EOF
}

scenario() { # $1 = label, $2 = cpu limit of each instance, $3... = instances
  local label="$1" cpus="$2"; shift 2
  local up=("$@") urls=() svc
  for svc in "${all_instances[@]}"; do
    if [[ " ${up[*]} " == *" $svc "* ]]; then continue; fi
    "${dc[@]}" stop "$svc" >/dev/null 2>&1 || true
  done
  LAB_SCALE_CPUS="$cpus" "${dc[@]}" up -d --no-deps "${up[@]}" >/dev/null 2>&1
  wait_healthy "${up[@]}"
  for svc in "${up[@]}"; do urls+=("http://$svc:8081/api/v1/products?size=50"); done
  load 10 "${urls[@]}" >/dev/null
  ( sleep $((measure / 2)); docker stats --no-stream --format '  {{.Name}} cpu={{.CPUPerc}} memory={{.MemUsage}}' \
      $(for svc in "${up[@]}"; do "${dc[@]}" ps -q "$svc"; done) ) > /tmp/scale-stats.txt &
  read -r ok err elapsed < <(load "$measure" "${urls[@]}" | tail -1)
  wait
  echo "$label: instances=${#up[@]} cpu_each=$cpus requests_200=$ok errors=$err seconds=$elapsed throughput=$((ok / elapsed)) req/s"
  cat /tmp/scale-stats.txt
  for svc in "${up[@]}"; do
    docker inspect -f '  limits: {{.Name}} NanoCpus={{.HostConfig.NanoCpus}} MemoryBytes={{.HostConfig.Memory}}' "$("${dc[@]}" ps -q "$svc")"
  done
}

scenario "A one instance"    "$cpus_each" catalog
scenario "B two instances"   "$cpus_each" catalog catalog-2
scenario "C three instances" "$cpus_each" catalog catalog-2 catalog-3
scenario "D one bigger instance (3 x CPU)" "$(awk "BEGIN{print 3 * $cpus_each}")" catalog

"${dc[@]}" stop catalog-2 catalog-3 >/dev/null 2>&1 || true
docker compose -f compose.yaml -f compose.labs.yaml up -d catalog >/dev/null 2>&1
echo "catalog recreated without limits"
