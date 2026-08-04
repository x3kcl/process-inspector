#!/usr/bin/env bash
# Idempotent seed script for the dockerized Flowable engines (docker/docker-compose.dev.yml).
# REST-only — never touches engine tables (CLAUDE.md iron rule).
#
# Usage:
#   docker/seed.sh                              # auto-discover: every reachable known engine
#   docker/seed.sh --profile <name>             # seed ONLY that profile's engines (CI)
#   docker/seed.sh <base-url> [user:pass]       # seed exactly that engine
#
# --profile is required in CI: auto-discover would seed ANY reachable engine on the
# shared Docker host — including another slot's concurrent flap/flowable-7 — and a
# mis-resolved PI_ENGINE_FLAP_PORT (fallback default = s1's 8695) caused exactly that
# cross-slot race (PR #364, run 30927869125: flowable-6 on s6 seeded s1's flap).
#
# Deployment idempotency is BY KEY: editing a process file does not redeploy it here —
# reset the harness (docker compose down -v) or deploy the new version manually.
# Instance starts are NOT idempotent by design: every run adds one instance per arc
# (the dev playground grows; CI runners are ephemeral).
set -euo pipefail

# The flowable-rest images' well-known account. Reassigned per engine during discovery —
# every helper below reads this global — because the `flap` engine is an APPLICATION that
# embeds Flowable and issues its own service account instead.
CRED="rest-admin:test"
DIR="$(cd "$(dirname "$0")" && pwd)"
# One descriptor per harness engine: port|context-path|credentials. Ports are
# env-overridable (default = doctrinal dev ports) to match a remapped self-hosted-runner
# harness; see docker-compose.dev.yml PI_ENGINE_*_PORT. The last entry is the Boot layout —
# an embedded engine publishes /process-api at the root, not one /flowable-rest context.
ENGINE_A="${PI_ENGINE_A_PORT:-8081}|/flowable-rest/service|rest-admin:test"
ENGINE_B="${PI_ENGINE_B_PORT:-8082}|/flowable-rest/service|rest-admin:test"
ENGINE_7="${PI_ENGINE_7_PORT:-8083}|/flowable-rest/service|rest-admin:test"
ENGINE_LEGACY="${PI_ENGINE_LEGACY_PORT:-8084}|/flowable-rest/service|rest-admin:test"
ENGINE_FLAP="${PI_ENGINE_FLAP_PORT:-8086}|/process-api|inspector:harness"
KNOWN_ENGINES="$ENGINE_A
$ENGINE_B
$ENGINE_7
$ENGINE_LEGACY
$ENGINE_FLAP"

# Map a smoke-test / ci.yml matrix profile to the engine descriptors it boots.
# Keep in lockstep with docker/smoke-test.sh's case arms.
engines_for_profile() {
  case "$1" in
    flowable-6) echo "$ENGINE_A
$ENGINE_B" ;;
    flowable-7) echo "$ENGINE_7" ;;
    legacy) echo "$ENGINE_LEGACY" ;;
    flap) echo "$ENGINE_FLAP" ;;
    migration-findings) echo "$ENGINE_A
$ENGINE_B
$ENGINE_7" ;;
    *) echo "unknown profile: $1 (want flowable-6|flowable-7|legacy|flap|migration-findings)" >&2; return 2 ;;
  esac
}

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
  # ...and one left ACTIVE as the task/variable-edit target. Carries typed variables so
  # the §4a editor arcs (usability harness M3: number edit, empty-vs-null clear) have
  # real rows to edit.
  pid=$(start_instance "$E" '{"processDefinitionKey":"demoUserTask","variables":[
    {"name":"amount","type":"integer","value":100},{"name":"note","type":"string","value":"temporary hold"}]}')
  echo "  demoUserTask       $pid (ACTIVE on user task; amount/note edit targets)"

  # Timer-stuck ACTIVE (FIX-PROC-03) — PT24H: present in the timer lane, never overdue.
  pid=$(start_instance "$E" '{"processDefinitionKey":"demoTimerWait","variables":[
    {"name":"dueDuration","type":"string","value":"PT24H"}]}')
  echo "  demoTimerWait      $pid (stuck on PT24H timer)"

  # failedInSubprocess roll-up (FIX-PROC-06): parent waits, CHILD dead-letters.
  pid=$(start_instance "$E" "{\"processDefinitionKey\":\"demoParent\",\"businessKey\":\"seed-$(date +%s)\",\"variables\":[
    {\"name\":\"amount\",\"type\":\"integer\",\"value\":100},{\"name\":\"divisor\",\"type\":\"integer\",\"value\":0}]}")
  echo "  demoParent         $pid (child will dead-letter)"

  # Usability-harness fixtures (docs/usability/GOAL-CATALOG.md FIXTURE GAPS):
  # F-G1 wide MI parent (R-SEM-19 hierarchy breadth cap / timeline sub-lanes). Guarded —
  # one live 60-child fan-out per engine is plenty; reseeds must not accumulate them.
  deploy_if_missing "$E" demoWideChild  demo-wide-child.bpmn20.xml
  deploy_if_missing "$E" demoWideParent demo-wide-parent.bpmn20.xml
  local wide
  wide=$(curl -sfu "$CRED" "$E/runtime/process-instances?processDefinitionKey=demoWideParent&size=1" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["total"])')
  if [ "$wide" = "0" ]; then
    pid=$(start_instance "$E" '{"processDefinitionKey":"demoWideParent","businessKey":"ORD-BATCH-2107"}')
    echo "  demoWideParent     $pid (60 parallel MI children — breadth-cap fixture)"
  else
    echo "  demoWideParent     already live ($wide active) — skipping."
  fi

  # F-G6 hostile-text instance (R-OPS-08 injection rendering must stay inert). Guarded
  # via the query API (the business key itself is the hostile payload).
  local hostile
  hostile=$(curl -sfu "$CRED" -H 'Content-Type: application/json' -X POST \
    -d '{"processDefinitionKey":"demoUserTask","businessKey":"<img src=x onerror=alert(1)>"}' \
    "$E/query/process-instances?size=1" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["total"])')
  if [ "$hostile" = "0" ]; then
    pid=$(start_instance "$E" '{"processDefinitionKey":"demoUserTask","businessKey":"<img src=x onerror=alert(1)>","variables":[
      {"name":"note","type":"string","value":"=HYPERLINK(\"http://evil.example\",\"open\")"},
      {"name":"payload","type":"string","value":"<script>alert(\"xss\")</script>"}]}')
    echo "  demoUserTask       $pid (HOSTILE businessKey/vars — injection fixture)"
  else
    echo "  hostile fixture    already live — skipping."
  fi

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

  # issue #359 (RETRYING-RISK-LANE.md §7.2/G12) — the ONE transiently-failing seed process in
  # the harness (validate-bpmn: clock-driven, via demo-self-healing.bpmn20.xml's boundary
  # timer). OFF by default, opt in with PI_SEED_SELF_HEALING=1: every OTHER fixture here is
  # what the R4 grouping-quality corpus (backend/src/test/resources/grouping-quality/corpus.json,
  # docs/reviews/R4-GROUPING-QUALITY-2026-08.md) and the R2 self-heal baseline
  # (docs/reviews/R2-SELFHEAL-BASELINE-2026-08.md) were measured against — both are static,
  # committed snapshots, so this fixture can never silently invalidate them, but seeding it by
  # default would still shift "what the demo/dev fleet looks like" for anyone who reseeds after
  # this change, which the issue says to coordinate rather than do quietly. It also never counts
  # toward the §7.2 production data-maturity gate either way — that gate reads real engine
  # history over REST and has no way to tell a harness fixture's spells apart from organic ones,
  # which is exactly why this stays opt-in on any deployment that cares about that gate's honesty
  # (the demo/pilot). demoSelfHealingBaseline goes first: it establishes the standing dead-letter
  # that keeps the shared class observable across demoSelfHealing's retrying-then-healed dips
  # (see that file's own doc comment).
  if [ "${PI_SEED_SELF_HEALING:-0}" = "1" ]; then
    if python3 -c "import sys;v=('$ver'+'.0.0').split('.');sys.exit(0 if (int(v[0])>6 or (int(v[0])==6 and int(v[1])>=8)) else 1)" 2>/dev/null; then
      deploy_if_missing "$E" demoSelfHealingBaseline demo-self-healing-baseline.bpmn20.xml
      deploy_if_missing "$E" demoSelfHealing         demo-self-healing.bpmn20.xml
      pid=$(start_instance "$E" '{"processDefinitionKey":"demoSelfHealingBaseline","variables":[]}')
      echo "  demoSelfHealingBaseline $pid (standing dead-letter — keeps the class observable)"
      pid=$(start_instance "$E" '{"processDefinitionKey":"demoSelfHealing","variables":[
        {"name":"healDelay","type":"string","value":"PT30S"}]}')
      echo "  demoSelfHealing         $pid (clock-driven — self-heals ~30s after its first failure)"
    else
      echo "  demoSelfHealing skipped — engine $ver predates the boundary-timer construct (< 6.8)."
    fi
  fi

  seed_acme "$E"
}

seed_descriptors() { # newline-separated port|path|cred descriptors
  local seeded=0 ports="" port path cred url
  while IFS='|' read -r port path cred; do
    [ -n "$port" ] || continue
    ports="$ports $port"
    url="http://localhost:$port$path"
    CRED="$cred"
    # -sf alone exits 0 on a 3xx, so an app that redirects an unmapped/closed REST path to its
    # login page would be "discovered" and then fail on the first real call. Require the 200.
    if [ "$(curl -sfu "$CRED" --connect-timeout 2 --max-time 5 -o /dev/null \
              -w '%{http_code}' "$url/management/engine" 2>/dev/null)" = "200" ]; then
      seed_engine "$url"
      seeded=$((seeded + 1))
    else
      echo "Engine on :$port not reachable — skipping."
    fi
  done <<< "$1"
  if [ "$seeded" = "0" ]; then
    echo "ERROR: no engine reachable on any of:$ports — start the harness first:" >&2
    echo "  docker compose -f docker/docker-compose.dev.yml up -d" >&2
    exit 1
  fi
  echo "Seeded $seeded engine(s)."
}

if [ "${1:-}" = "--profile" ]; then
  PROFILE="${2:?usage: seed.sh --profile <flowable-6|flowable-7|legacy|flap|migration-findings>}"
  seed_descriptors "$(engines_for_profile "$PROFILE")"
elif [ $# -ge 1 ]; then
  CRED="${2:-$CRED}"
  seed_engine "$1"
else
  seed_descriptors "$KNOWN_ENGINES"
fi
