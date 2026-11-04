#!/usr/bin/env bash
# Webhook signature verification (raw body + constant-time HMAC-SHA256) and idempotent webhook retry lab
# (Phase 9, unit 9.3), against the running warehouse service.
#
#   docker compose -f compose.yaml -f compose.labs.yaml up --build -d
#   labs/messaging/webhook-lab.sh <admin-token>
#
# Run from the repository root.
set -euo pipefail

token="${1:?usage: webhook-lab.sh <admin-token>}"
dc=(docker compose -f compose.yaml -f compose.labs.yaml)
secret="whsec_stockflow_lab_default_signing_secret_32b"
stock_url="http://warehouse:8082/api/v1/stock"
wh_url="http://warehouse:8082/api/v1/webhooks/stock-events"

field() { sed -n "s/.*\"$1\":\([0-9]*\).*/\1/p" | head -1; }

stock_id=$("${dc[@]}" exec -T gateway wget -qO- \
  --header='Content-Type: application/json' \
  --header="Authorization: Bearer $token" \
  --post-data="{\"locationId\":1,\"productId\":$(date +%s%N),\"quantity\":10}" \
  "$stock_url" < /dev/null | field id)

left() {
  "${dc[@]}" exec -T gateway wget -qO- \
    --header="Authorization: Bearer $token" \
    "$stock_url/$stock_id" < /dev/null | field quantity
}

sign() { # $1 = timestamp, $2 = raw body
  printf '%s.%s' "$1" "$2" | openssl dgst -sha256 -hmac "$secret" | sed 's/^.* //'
}

send_webhook() { # $1 = webhook_id, $2 = timestamp, $3 = signature, $4 = raw_body
  "${dc[@]}" exec -T gateway sh -c \
    "wget -S -q -O /dev/null --header='Content-Type: application/json' --header='X-Webhook-Id: $1' --header='X-Webhook-Timestamp: $2' --header='X-Webhook-Signature: sha256=$3' --post-data='$4' '$wh_url' 2>&1 | sed -n 's/.*HTTP\/1.1 \([0-9]*\).*/\1/p' | tail -1" < /dev/null | tr -d '\r'
}

now=$(date +%s)
compact="{\"eventType\":\"STOCK_ADJUSTED\",\"stockId\":$stock_id,\"delta\":-3}"
pretty="{ \"eventType\": \"STOCK_ADJUSTED\", \"stockId\": $stock_id, \"delta\": -3 }"
sig=$(sign "$now" "$compact")
wh_id="wh-live-$(date +%s%N)"

echo "1. valid HMAC over raw body: status $(send_webhook "$wh_id" "$now" "$sig" "$compact"), units left $(left) (10 - 3 = 7)"
echo "2. duplicate retry with same X-Webhook-Id: status $(send_webhook "$wh_id" "$now" "$sig" "$compact"), units left $(left) (7)"
echo "3. same JSON fields with extra spaces (raw bytes differ): status $(send_webhook "$wh_id-ws" "$now" "$sig" "$pretty") (401), units left $(left) (7)"

stale=$((now - 600))
stale_sig=$(sign "$stale" "$compact")
echo "4. valid HMAC but timestamp 10 min old: status $(send_webhook "$wh_id-old" "$stale" "$stale_sig" "$compact") (401), units left $(left) (7)"
