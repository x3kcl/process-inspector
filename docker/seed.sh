#!/usr/bin/env bash
# Idempotent seed script for a Flowable REST engine (docker/docker-compose.dev.yml).
# REST-only — never touches engine tables (CLAUDE.md iron rule).
#
# Usage: docker/seed.sh [engine-base-url]
#   default engine-base-url: http://localhost:8081/flowable-rest/service
set -euo pipefail

ENGINE="${1:-http://localhost:8081/flowable-rest/service}"
CRED="rest-admin:test"
DIR="$(cd "$(dirname "$0")" && pwd)"

deploy_if_missing() {
  local key="$1" file="$2"
  local total
  total=$(curl -sfu "$CRED" "$ENGINE/repository/process-definitions?key=$key&latest=true" \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["total"])')
  if [ "$total" = "0" ]; then
    echo "Deploying $file ..."
    curl -sfu "$CRED" -F "file=@$DIR/processes/$file" "$ENGINE/repository/deployments" >/dev/null
    # Deployment 2xx != definition parsed — assert the definition list (validate-bpmn §3).
    total=$(curl -sfu "$CRED" "$ENGINE/repository/process-definitions?key=$key&latest=true" \
      | python3 -c 'import sys,json; print(json.load(sys.stdin)["total"])')
    if [ "$total" = "0" ]; then
      echo "ERROR: $file deployed but definition '$key' did not appear — parse failure?" >&2
      exit 1
    fi
  else
    echo "Definition '$key' already present — skipping deploy."
  fi
}

start_instance() {
  local key="$1" variables="$2"
  curl -sfu "$CRED" -H 'Content-Type: application/json' \
    -d "{\"processDefinitionKey\":\"$key\",\"variables\":$variables}" \
    "$ENGINE/runtime/process-instances" \
    | python3 -c 'import sys,json; print("Started", json.load(sys.stdin)["id"])'
}

deploy_if_missing demoOrder demo-order.bpmn20.xml
deploy_if_missing demoFailingPayment demo-failing-payment.bpmn20.xml

# One completed instance for the historic views:
start_instance demoOrder '[]'
# One organically dead-lettering instance (R1/PT1S => DLQ within ~2s):
start_instance demoFailingPayment \
  '[{"name":"amount","type":"integer","value":100},{"name":"divisor","type":"integer","value":0}]'

echo "Seeded $ENGINE"
