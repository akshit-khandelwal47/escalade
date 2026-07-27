#!/usr/bin/env bash
# Walks the full lifecycle against a running instance: trigger -> dedup -> escalate -> ack.
# Written to be watchable: it pauses between beats so the escalation can be seen happening.
#
#   BASE=http://localhost:8080 KEY=esc_demo_key_local ./scripts/demo.sh
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
KEY="${KEY:-esc_demo_key_local}"
AUTH=(-H "Authorization: Bearer $KEY" -H "Content-Type: application/json")

B=$'\033[1m'; DIM=$'\033[2m'; G=$'\033[32m'; Y=$'\033[33m'; C=$'\033[36m'; R=$'\033[0m'
step() { printf '\n%s▸ %s%s\n' "$B$C" "$1" "$R"; }
note() { printf '%s  %s%s\n' "$DIM" "$1" "$R"; }

timeline() {
  curl -s "$BASE/api/v1/incidents/$1" "${AUTH[@]}" \
    | jq -r '"  status=\(.status)  step=\(.currentStep)",
             (.timeline[] | "    step \(.stepOrder)  \(.channel)  \(.status)")'
}

step "Create an escalation policy: Slack now, email after 5s, escalate to leads after 60s"
POLICY=$(curl -s -X POST "$BASE/api/v1/policies" "${AUTH[@]}" -d '{
  "name": "Backend on-call",
  "steps": [
    {"channel": "SLACK", "target": "#alerts",            "delaySeconds": 0},
    {"channel": "EMAIL", "target": "oncall@example.com", "delaySeconds": 5},
    {"channel": "SLACK", "target": "#eng-leads",         "delaySeconds": 60}
  ]}' | jq -r .id)
printf '  policy %s\n' "$POLICY"

step "Monitoring fires an incident"
INC=$(curl -s -X POST "$BASE/api/v1/incidents" "${AUTH[@]}" -d "{
  \"policyId\": \"$POLICY\", \"title\": \"High DB latency\", \"dedupKey\": \"db-latency-prod\"}" | jq -r .id)
printf '  incident %s\n' "$INC"

step "The same alert fires again (monitoring retried on timeout)"
CODE=$(curl -s -o /tmp/dedup.json -w '%{http_code}' -X POST "$BASE/api/v1/incidents" "${AUTH[@]}" -d "{
  \"policyId\": \"$POLICY\", \"title\": \"High DB latency\", \"dedupKey\": \"db-latency-prod\"}")
if [ "$(jq -r .id /tmp/dedup.json)" = "$INC" ]; then
  printf '  %sHTTP %s — same incident returned, nobody paged twice%s\n' "$G" "$CODE" "$R"
fi

step "Worker delivers step 0 and schedules step 1"
sleep 3
timeline "$INC"

step "Nobody acknowledges — escalation advances to email on its own"
sleep 6
timeline "$INC"
note "step 2 (#eng-leads) is now pending, waiting out its delay"

step "On-call acknowledges before it reaches the leads channel"
curl -s -X POST "$BASE/api/v1/incidents/$INC/ack" "${AUTH[@]}" > /dev/null
timeline "$INC"
printf '\n  %sacknowledged — the pending step is CANCELLED and will never page%s\n\n' "$G" "$R"
