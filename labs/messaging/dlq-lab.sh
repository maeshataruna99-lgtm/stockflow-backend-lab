#!/usr/bin/env bash
# Message queue, idempotent consumer, dead-letter queue and bounded queue lab (Phase 9, units 9.2 and 9.7),
# against the running RabbitMQ container (compose profile "messaging").
#
#   docker compose -f compose.yaml -f compose.labs.yaml --profile messaging up -d rabbitmq
#   labs/messaging/dlq-lab.sh
#
# Run from the repository root.
set -euo pipefail

dc=(docker compose -f compose.yaml -f compose.labs.yaml --profile messaging)

"${dc[@]}" exec -T rabbitmq sh -s <<'EOF'
set -eu

for q in lab.work lab.bounded lab.dlq; do
  rabbitmqadmin delete queue name="$q" >/dev/null 2>&1 || true
done
for ex in lab.events lab.dlx; do
  rabbitmqadmin delete exchange name="$ex" >/dev/null 2>&1 || true
done

rabbitmqadmin declare exchange name=lab.events type=direct durable=true >/dev/null
rabbitmqadmin declare exchange name=lab.dlx type=direct durable=true >/dev/null
rabbitmqadmin declare queue name=lab.dlq durable=true >/dev/null
rabbitmqadmin declare binding source=lab.dlx destination=lab.dlq routing_key=lab.dlq >/dev/null
rabbitmqadmin declare queue name=lab.work durable=true \
  'arguments={"x-dead-letter-exchange":"lab.dlx","x-dead-letter-routing-key":"lab.dlq"}' >/dev/null
rabbitmqadmin declare binding source=lab.events destination=lab.work routing_key=stock.changed >/dev/null

# 1. Publish a poison message (3 retries then DLQ) and a valid message behind it
rabbitmqadmin publish exchange=lab.events routing_key=stock.changed \
  'properties={"message_id":"evt-poison-1"}' payload='{"stockId":1,"delta":"bad"}' >/dev/null
rabbitmqadmin publish exchange=lab.events routing_key=stock.changed \
  'properties={"message_id":"evt-valid-2"}' payload='{"stockId":1,"delta":-3}' >/dev/null

# Retry the poison message twice (requeue=true), then reject with requeue=false on attempt 3
rabbitmqadmin get queue=lab.work ackmode=reject_requeue_true >/dev/null
rabbitmqadmin get queue=lab.work ackmode=reject_requeue_true >/dev/null
rabbitmqadmin get queue=lab.work ackmode=reject_requeue_false >/dev/null

# Consume the valid message that was behind the poison message
valid_msg=$(rabbitmqadmin get queue=lab.work ackmode=ack_requeue_false -f raw_json)
dlq_msg=$(rabbitmqadmin get queue=lab.dlq ackmode=ack_requeue_false -f raw_json)
echo "1. poison message after 3 attempts -> DLQ: $(echo "$dlq_msg" | sed -n 's/.*"message_id":"\([^"]*\)".*"x-first-death-reason":"\([^"]*\)".*/message_id=\1 reason=\2/p')"
echo "2. valid message behind poison consumed from lab.work: $(echo "$valid_msg" | sed -n 's/.*"message_id":"\([^"]*\)".*"payload":"\([^"]*\)".*/message_id=\1 payload=\2/p')"

# 3. Bounded queue (x-max-length=3) drops and dead-letters oldest messages when 5 are published
rabbitmqadmin declare queue name=lab.bounded durable=true \
  'arguments={"x-max-length":3,"x-dead-letter-exchange":"lab.dlx","x-dead-letter-routing-key":"lab.dlq"}' >/dev/null
for i in 1 2 3 4 5; do
  rabbitmqadmin publish exchange=amq.default routing_key=lab.bounded \
    "properties={\"message_id\":\"cap-$i\"}" payload="msg-$i" >/dev/null
done
kept=$(rabbitmqadmin get queue=lab.bounded count=10 ackmode=ack_requeue_false -f raw_json | grep -o '"message_id":"cap-[0-9]"' | tr '\n' ' ')
evicted=$(rabbitmqadmin get queue=lab.dlq count=10 ackmode=ack_requeue_false -f raw_json | grep -o '"message_id":"cap-[0-9]"' | tr '\n' ' ')
echo "3. bounded queue (x-max-length=3, 5 published): kept=[$kept] dead-lettered(reason=maxlen)=[$evicted]"

for q in lab.work lab.bounded lab.dlq; do
  rabbitmqadmin delete queue name="$q" >/dev/null 2>&1 || true
done
for ex in lab.events lab.dlx; do
  rabbitmqadmin delete exchange name="$ex" >/dev/null 2>&1 || true
done
EOF
