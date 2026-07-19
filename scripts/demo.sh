#!/usr/bin/env bash
# Smoke-test the Escalade API end to end against a running instance.
# Usage: BASE=http://localhost:8080 KEY=esc_demo_key_local ./scripts/demo.sh
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
KEY="${KEY:-esc_demo_key_local}"
AUTH=(-H "Authorization: Bearer $KEY" -H "Content-Type: application/json")

echo "==> Create policy"
POLICY_ID=$(curl -s -X POST "$BASE/api/v1/policies" "${AUTH[@]}" -d '{
  "name": "Backend on-call",
  "steps": [
    {"channel": "SLACK", "target": "#alerts",           "delaySeconds": 0},
    {"channel": "EMAIL", "target": "oncall@example.com", "delaySeconds": 30}
  ]
}' | jq -r .id)
echo "policy: $POLICY_ID"

echo "==> Trigger incident (expect HTTP 201)"
curl -s -o /tmp/inc1.json -w "status=%{http_code}\n" -X POST "$BASE/api/v1/incidents" "${AUTH[@]}" -d "{
  \"policyId\": \"$POLICY_ID\", \"title\": \"High DB latency\", \"dedupKey\": \"db-latency-prod\"
}"
INCIDENT_ID=$(jq -r .id /tmp/inc1.json)
echo "incident: $INCIDENT_ID"; jq . /tmp/inc1.json

echo "==> Retry SAME dedupKey (expect HTTP 200, same id — no duplicate page)"
curl -s -o /tmp/inc2.json -w "status=%{http_code}\n" -X POST "$BASE/api/v1/incidents" "${AUTH[@]}" -d "{
  \"policyId\": \"$POLICY_ID\", \"title\": \"High DB latency\", \"dedupKey\": \"db-latency-prod\"
}"
echo "same incident? $([ "$(jq -r .id /tmp/inc2.json)" = "$INCIDENT_ID" ] && echo yes || echo NO)"

echo "==> Acknowledge (expect status ACKNOWLEDGED)"
curl -s -X POST "$BASE/api/v1/incidents/$INCIDENT_ID/ack" "${AUTH[@]}" | jq '{status, ackedAt}'

echo "==> Timeline"
curl -s "$BASE/api/v1/incidents/$INCIDENT_ID" "${AUTH[@]}" | jq '{status, timeline}'
