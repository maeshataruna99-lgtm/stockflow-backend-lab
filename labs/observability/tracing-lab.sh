#!/usr/bin/env bash
# Observability & Distributed Tracing lab (Phase 10, Unit 10.2 / Topic 50):
#   1. Starts Jaeger (`jaegertracing/all-in-one:1.62.0`) under the `observability` Compose profile.
#   2. Obtains a demo admin JWT from `api-gateway` using `ROOT_USERNAME` / `ROOT_PASSWORD` from `.env`.
#   3. Sends a traced request with an explicit W3C `traceparent` and `X-Request-Id` through `api-gateway`
#      to `warehouse-service` (`GET /api/v1/warehouses`).
#   4. Queries Jaeger's HTTP API (`http://jaeger:16686/api/traces/<traceId>`) to prove both `api-gateway`
#      and `warehouse-service` exported spans sharing `<traceId>`.
#   5. Prints the correlated structured log line from `warehouse-service`, showing `requestId`, `traceId`,
#      and `[REDACTED]` in place of the `Authorization` bearer token and `password` query parameter.
#
# Run from the repository root:
#   bash labs/observability/tracing-lab.sh

set -eu
export MSYS_NO_PATHCONV=1

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

ROOT_USER="${ROOT_USERNAME:-root}"
ROOT_PASS="${ROOT_PASSWORD:-root_demo_password_123}"

COMPOSE="docker compose -f compose.yaml -f compose.labs.yaml --profile observability"

echo "--- Starting Jaeger (observability profile) ---"
$COMPOSE up -d jaeger < /dev/null

echo "--- Waiting for Jaeger query API ---"
attempt=0
until $COMPOSE exec -T warehouse wget -q -O /dev/null "http://jaeger:16686/api/services" < /dev/null 2>/dev/null; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 30 ]; then
    echo "jaeger did not become ready in time" >&2
    exit 1
  fi
  sleep 1
done

echo "--- Obtaining demo admin JWT from api-gateway ---"
login_resp=$(curl -s -X POST "http://localhost:8080/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"${ROOT_USER}\",\"password\":\"${ROOT_PASS}\"}")
token=$(printf '%s' "$login_resp" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
if [ -z "$token" ]; then
  echo "Could not obtain accessToken from gateway: $login_resp" >&2
  exit 1
fi

trace_id=$(printf '%08x%08x%08x%08x' $RANDOM$RANDOM $RANDOM$RANDOM $RANDOM$RANDOM $RANDOM$RANDOM | cut -c1-32)
parent_span_id="00f067aa0ba902b7"
req_id="req-trace-lab-$(date +%s)"

echo "--- Sending traced request through api-gateway (traceId=$trace_id, requestId=$req_id) ---"
resp_headers=$(curl -s -i "http://localhost:8080/api/v1/warehouses?password=shouldBeRedacted" \
  -H "Authorization: Bearer $token" \
  -H "X-Request-Id: $req_id" \
  -H "traceparent: 00-${trace_id}-${parent_span_id}-01")

http_code=$(printf "%s" "$resp_headers" | head -n 1 | tr -d "\r")
echo "gateway HTTP status = $http_code"

echo "--- Querying Jaeger for traceId=$trace_id ---"
sleep 2
trace_json=$($COMPOSE exec -T warehouse wget -q -O - "http://jaeger:16686/api/traces/${trace_id}" < /dev/null)
services=$(printf '%s' "$trace_json" | grep -o '"serviceName":"[^"]*"' | sort -u | tr '\n' ' ')
span_count=$(printf '%s' "$trace_json" | grep -o '"spanID":"[^"]*"' | wc -l | tr -d ' ')

echo "Jaeger traceId   = $trace_id"
echo "Jaeger services  = $services"
echo "Jaeger spans     = $span_count"

echo "--- Checking warehouse-service structured correlation log & redaction ---"
$COMPOSE logs warehouse --no-log-prefix < /dev/null | grep "$req_id" | tail -n 1
