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
# Env-overridable (default = doctrinal dev ports) to match a remapped self-hosted-runner
# harness; see docker-compose.dev.yml PI_ENGINE_*_PORT.
KNOWN_PORTS="${PI_ENGINE_A_PORT:-8081} ${PI_ENGINE_B_PORT:-8082} ${PI_ENGINE_7_PORT:-8083} ${PI_ENGINE_LEGACY_PORT:-8084}"

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

task_for_instance() { # engine instance-id -> first open task id (or empty)
  curl -sfu "$CRED" "$1/runtime/tasks?processInstanceId=$2" \
    | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print(d[0]["id"] if d else "")'
}

complete_task() { # engine task-id
  curl -sfu "$CRED" -X POST -H 'Content-Type: application/json' -d '{"action":"complete"}' \
    "$1/runtime/tasks/$2" >/dev/null
}

# Correlate a named message to the single execution of one instance parked at activityId.
# This is the request/response leg of the event choreography — delivered over REST, targeting
# exactly the waiting order (correlation by instance), never a broadcast (validate-bpmn §5).
deliver_message() { # engine instance-id activityId messageName
  local ex
  ex=$(curl -sfu "$CRED" "$1/runtime/executions?processInstanceId=$2&activityId=$3" \
    | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print(d[0]["id"] if d else "")')
  [ -n "$ex" ] && curl -sfu "$CRED" -X PUT -H 'Content-Type: application/json' \
    -d "{\"action\":\"messageEventReceived\",\"messageName\":\"$4\"}" \
    "$1/runtime/executions/$ex" >/dev/null
}

engine_is_68plus() { # engine -> exit 0 iff Flowable >= 6.8
  local ver
  ver=$(curl -sfu "$CRED" "$1/management/engine" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin).get("version",""))')
  python3 -c "import sys;v=('$ver'+'.0.0').split('.');sys.exit(0 if (int(v[0])>6 or (int(v[0])==6 and int(v[1])>=8)) else 1)" 2>/dev/null
}

# ACME back-office suite: swimlane/department approvals, multi-gateway processes, public-API
# (HTTP-task) integrations, and event-based inter-process choreography with correlation.
# Gated to 6.8+ (consistent with the modern-engine fixtures; 6.3 is DLQ-blind — see memory).
seed_acme() { # engine
  local E="$1" pid tid first fin leg combo
  if ! engine_is_68plus "$E"; then
    echo "  ACME suite skipped — engine predates 6.8 (swimlane/http/event fixtures gated 6.8+)."
    return
  fi
  echo "  -- ACME back-office suite --"
  deploy_if_missing "$E" acmeExpenseApproval     acme-expense-approval.bpmn20.xml
  deploy_if_missing "$E" acmeLeaveRequest        acme-leave-request.bpmn20.xml
  deploy_if_missing "$E" acmePurchaseRequisition acme-purchase-requisition.bpmn20.xml
  deploy_if_missing "$E" acmeLoanOrigination     acme-loan-origination.bpmn20.xml
  deploy_if_missing "$E" acmeVendorEnrichment    acme-vendor-enrichment.bpmn20.xml
  deploy_if_missing "$E" acmeApiOutage           acme-api-outage.bpmn20.xml
  deploy_if_missing "$E" acmePaymentService      acme-payment-service.bpmn20.xml
  deploy_if_missing "$E" acmeOrderOrchestrator   acme-order-orchestrator.bpmn20.xml

  # Expense — three amount bands (auto-approve / Finance / Finance+Director). Complete the
  # employee entry task so the token reaches the routing gateway and the department queue.
  for amt in 200 2000 9000; do
    pid=$(start_instance "$E" "{\"processDefinitionKey\":\"acmeExpenseApproval\",\"businessKey\":\"EXP-$amt-$(date +%s)\",\"variables\":[{\"name\":\"amount\",\"type\":\"integer\",\"value\":$amt}]}")
    tid=$(task_for_instance "$E" "$pid"); [ -n "$tid" ] && complete_task "$E" "$tid"
    echo "  acmeExpenseApproval     $pid (amount=$amt)"
  done

  # Leave — one instance fans to two concurrent department tasks (engineering + hr).
  pid=$(start_instance "$E" '{"processDefinitionKey":"acmeLeaveRequest","variables":[]}')
  tid=$(task_for_instance "$E" "$pid"); [ -n "$tid" ] && complete_task "$E" "$tid"
  echo "  acmeLeaveRequest        $pid (parallel team-lead + HR tasks open)"

  # Purchase requisition — inclusive-gateway fan-out combinations.
  for combo in 'true true' 'false false'; do
    read -r fin leg <<<"$combo"
    pid=$(start_instance "$E" "{\"processDefinitionKey\":\"acmePurchaseRequisition\",\"variables\":[{\"name\":\"needsFinance\",\"type\":\"boolean\",\"value\":$fin},{\"name\":\"needsLegal\",\"type\":\"boolean\",\"value\":$leg}]}")
    tid=$(task_for_instance "$E" "$pid"); [ -n "$tid" ] && complete_task "$E" "$tid"
    echo "  acmePurchaseRequisition $pid (needsFinance=$fin needsLegal=$leg)"
  done

  # Loan — complete the capture task so the public-API address call + parallel checks run;
  # the token then parks on Income verification (Finance). creditScore drives the later arm.
  for score in 780 640 550; do
    pid=$(start_instance "$E" "{\"processDefinitionKey\":\"acmeLoanOrigination\",\"variables\":[{\"name\":\"creditScore\",\"type\":\"integer\",\"value\":$score}]}")
    tid=$(task_for_instance "$E" "$pid"); [ -n "$tid" ] && complete_task "$E" "$tid"
    echo "  acmeLoanOrigination     $pid (creditScore=$score)"
  done

  # Public-API integrations (need outbound internet; the outage one fails deterministically).
  pid=$(start_instance "$E" '{"processDefinitionKey":"acmeVendorEnrichment","variables":[]}')
  echo "  acmeVendorEnrichment    $pid (live HTTP GET -> parks at reviewVendor)"
  pid=$(start_instance "$E" '{"processDefinitionKey":"acmeApiOutage","variables":[]}')
  echo "  acmeApiOutage           $pid (async HTTP to .invalid host -> dead-letters)"

  # Event choreography — each orchestrator throws orderPlaced, auto-starting a payment-service
  # (signal, inter-process). Order #1 receives its correlated paymentConfirmed and ships; #2/#3
  # stay parked at the event-based gateway as waiting-on-event fixtures.
  first=""
  for n in 1 2 3; do
    pid=$(start_instance "$E" "{\"processDefinitionKey\":\"acmeOrderOrchestrator\",\"businessKey\":\"ORD-$n-$(date +%s)\",\"variables\":[{\"name\":\"orderId\",\"type\":\"string\",\"value\":\"ORD-$n\"}]}")
    echo "  acmeOrderOrchestrator   $pid (orderId=ORD-$n; signalled acmePaymentService)"
    [ -z "$first" ] && first="$pid"
  done
  deliver_message "$E" "$first" catchPayment paymentConfirmed
  echo "  -> delivered correlated paymentConfirmed to $first (order shipped)"
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

  # External-worker fixture (v1.x #7) — the element is Flowable 6.8+ ONLY, so deploy it just
  # on capable engines; on 6.3 legacy the deploy would fail and there is no fifth queue to see.
  ver=$(curl -sfu "$CRED" "$E/management/engine" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("version",""))')
  if python3 -c "import sys;v=('$ver'+'.0.0').split('.');sys.exit(0 if (int(v[0])>6 or (int(v[0])==6 and int(v[1])>=8)) else 1)" 2>/dev/null; then
    deploy_if_missing "$E" demoExternalWorker demo-external-worker.bpmn20.xml
    pid=$(start_instance "$E" '{"processDefinitionKey":"demoExternalWorker","variables":[]}')
    echo "  demoExternalWorker $pid (parks an UNACQUIRED external-worker job — the fifth queue)"
  else
    echo "  demoExternalWorker skipped — engine $ver predates external workers (< 6.8)."
  fi

  seed_acme "$E"
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
