#!/usr/bin/env bash
# Idempotent seed script for the dockerized Flowable engines (docker/docker-compose.dev.yml).
# REST-only — never touches engine tables (CLAUDE.md iron rule).
#
# Usage:
#   docker/seed.sh                 # auto-discover: seeds EVERY reachable engine (:8081-:8084)
#   docker/seed.sh <base-url>      # seed exactly that engine
#
# Deployment idempotency is BY KEY: editing a process file does not redeploy it here —
# reset the harness (docker compose down -v) or deploy the new version manually.
# Instance starts are NOT idempotent by design: every run adds one instance per arc
# (the dev playground grows; CI runners are ephemeral).
set -euo pipefail

CRED="rest-admin:test"
DIR="$(cd "$(dirname "$0")" && pwd)"
KNOWN_PORTS="8081 8082 8083 8084"

json_total() { python3 -c 'import sys,json; print(json.load(sys.stdin)["total"])'; }
json_id()    { python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])'; }

deploy_if_missing() { # engine key file
  local engine="$1" key="$2" file="$3" total
  total=$(curl -sfu "$CRED" "$engine/repository/process-definitions?key=$key&latest=true" | json_total)
  if [ "$total" = "0" ]; then
    echo "  deploying $file ..."
    curl -sfu "$CRED" -F "file=@$DIR/processes/$file" "$engine/repository/deployments" >/dev/null
    # Deployment 2xx != definition parsed — assert the definition list (validate-bpmn §3).
    total=$(curl -sfu "$CRED" "$engine/repository/process-definitions?key=$key&latest=true" | json_total)
    if [ "$total" = "0" ]; then
      echo "  ERROR: $file deployed but definition '$key' did not appear — parse failure?" >&2
      exit 1
    fi
  else
    echo "  definition '$key' present — skipping deploy."
  fi
}

start_instance() { # engine json-body -> instance id on stdout
  curl -sfu "$CRED" -H 'Content-Type: application/json' -d "$2" \
    "$1/runtime/process-instances" | json_id
}

seed_engine() { # base-url
  local E="$1" pid
  echo "Seeding $E"

  # Child before parent: demoParent's call activity references demoFailingPayment.
  deploy_if_missing "$E" demoOrder          demo-order.bpmn20.xml
  deploy_if_missing "$E" demoFailingPayment demo-failing-payment.bpmn20.xml
  deploy_if_missing "$E" demoFailingRetry   demo-failing-retry.bpmn20.xml
  deploy_if_missing "$E" demoUserTask       demo-user-task.bpmn20.xml
  deploy_if_missing "$E" demoTimerWait      demo-timer-wait.bpmn20.xml
  deploy_if_missing "$E" demoParent         demo-parent.bpmn20.xml

  # COMPLETED (FIX-PROC-01)
  pid=$(start_instance "$E" '{"processDefinitionKey":"demoOrder","variables":[]}')
  echo "  demoOrder          $pid (completes immediately)"

  # FAILED via organic dead-letter (FIX-PROC-04)
  pid=$(start_instance "$E" '{"processDefinitionKey":"demoFailingPayment","variables":[
    {"name":"amount","type":"integer","value":100},{"name":"divisor","type":"integer","value":0}]}')
  echo "  demoFailingPayment $pid (dead-letters organically)"

  # RETRYING pinned 1h in the timer table (FIX-PROC-05)
  pid=$(start_instance "$E" '{"processDefinitionKey":"demoFailingRetry","variables":[
    {"name":"amount","type":"integer","value":100},{"name":"divisor","type":"integer","value":0}]}')
  echo "  demoFailingRetry   $pid (RETRYING, pinned R10/PT1H)"

  # ACTIVE parked on a user task, then SUSPENDED over REST (FIX-PROC-02; a process
  # cannot suspend itself in BPMN — suspension is a runtime REST action).
  pid=$(start_instance "$E" '{"processDefinitionKey":"demoUserTask","variables":[]}')
  curl -sfu "$CRED" -X PUT -H 'Content-Type: application/json' \
    -d '{"action":"suspend"}' "$E/runtime/process-instances/$pid" >/dev/null
  echo "  demoUserTask       $pid (SUSPENDED via REST)"
  # ...and one left ACTIVE as the task/variable-edit target.
  pid=$(start_instance "$E" '{"processDefinitionKey":"demoUserTask","variables":[]}')
  echo "  demoUserTask       $pid (ACTIVE on user task)"

  # Timer-stuck ACTIVE (FIX-PROC-03) — PT24H: present in the timer lane, never overdue.
  pid=$(start_instance "$E" '{"processDefinitionKey":"demoTimerWait","variables":[
    {"name":"dueDuration","type":"string","value":"PT24H"}]}')
  echo "  demoTimerWait      $pid (stuck on PT24H timer)"

  # failedInSubprocess roll-up (FIX-PROC-06): parent waits, CHILD dead-letters.
  pid=$(start_instance "$E" "{\"processDefinitionKey\":\"demoParent\",\"businessKey\":\"seed-$(date +%s)\",\"variables\":[
    {\"name\":\"amount\",\"type\":\"integer\",\"value\":100},{\"name\":\"divisor\",\"type\":\"integer\",\"value\":0}]}")
  echo "  demoParent         $pid (child will dead-letter)"
}

if [ $# -ge 1 ]; then
  seed_engine "$1"
else
  seeded=0
  for port in $KNOWN_PORTS; do
    url="http://localhost:$port/flowable-rest/service"
    if curl -sfu "$CRED" --connect-timeout 2 --max-time 5 -o /dev/null "$url/management/engine"; then
      seed_engine "$url"
      seeded=$((seeded + 1))
    else
      echo "Engine on :$port not reachable — skipping."
    fi
  done
  if [ "$seeded" = "0" ]; then
    echo "ERROR: no engine reachable on any of: $KNOWN_PORTS — start the harness first:" >&2
    echo "  docker compose -f docker/docker-compose.dev.yml up -d" >&2
    exit 1
  fi
  echo "Seeded $seeded engine(s)."
fi
