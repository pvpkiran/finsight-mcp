#!/bin/bash
# FinSight MCP — Kafka topic initialisation

BOOTSTRAP=kafka:9092

echo "Waiting for Kafka broker..."
cub kafka-ready -b $BOOTSTRAP 1 30

echo "Creating FinSight topics..."

create_topic() {
  local TOPIC=$1
  local PARTITIONS=$2
  local RETENTION_MS=$3
  kafka-topics --bootstrap-server $BOOTSTRAP \
    --create --if-not-exists \
    --topic "$TOPIC" \
    --partitions "$PARTITIONS" \
    --replication-factor 1 \
    --config retention.ms="$RETENTION_MS" \
    --config cleanup.policy=delete \
    --config compression.type=lz4
  echo "  ✓ $TOPIC"
}

create_topic "finsight.payments.transactions"    4  604800000
create_topic "finsight.payments.reconciliation"  2  604800000
create_topic "finsight.payments.dlq"             1  2592000000
create_topic "finsight.fraud.scores"             4  259200000
create_topic "finsight.fraud.alerts"             2  604800000
create_topic "finsight.fraud.dlq"               1  2592000000
create_topic "finsight.banking.consent"          2  2592000000
create_topic "finsight.banking.account-data"     2  86400000
create_topic "finsight.banking.dlq"             1  2592000000
create_topic "finsight.system.async-jobs"        2  604800000
create_topic "finsight.system.audit"             2  2592000000
create_topic "finsight.system.dlq"              1  2592000000
create_topic "finsight.tool.invocations"         3  604800000
create_topic "finsight.payment.events"           3  604800000

echo "All topics created."
kafka-topics --bootstrap-server $BOOTSTRAP --list