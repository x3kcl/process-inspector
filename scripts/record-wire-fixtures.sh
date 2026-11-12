#!/usr/bin/env bash
# Records the flowable-rest wire fixtures under backend/src/test/resources/wire/<major>/ (#464).
#
# Seeds ONE throwaway engine strictly over REST (never SQL — CLAUDE.md iron rule) with the
# docker/processes demo set, waits for the failing jobs to dead-letter, then saves the real
# answers the rung-2 WireMock tests replay (ProcessApiClientWireShapeTest,
# TriageAggregationServiceTest, SearchServiceAggregateTest).
#
# usage:
#   docker run -d --rm --name wire-68 -p 127.0.0.1:18681:8080 \
#     -e FLOWABLE_REST_APP_ADMIN_USER-ID=rest-admin -e FLOWABLE_REST_APP_ADMIN_PASSWORD=test \
#     flowable/flowable-rest:6.8.0
#   scripts/record-wire-fixtures.sh 18681 6.8      # likewise 7.1.0 → 7.1
#   docker rm -f wire-68
# The engine must be FRESH: the expected counts in the tests assume exactly this seed.
set -euo pipefail
PORT=$1
MAJOR=$2
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/backend/src/test/resources/wire/$MAJOR"
P="$ROOT/docker/processes"
B="http://127.0.0.1:$PORT/flowable-rest/service"
mkdir -p "$OUT"

c() { curl -sf -u rest-admin:test "$@"; }
pretty() { python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin), indent=2, sort_keys=True))'; }
field() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2], {}, {"d": d}))' "$OUT/$1.json" "$2"; }
rec() { c "$B$2" | pretty > "$OUT/$1.json"; }
recpost() { c -H 'Content-Type: application/json' -d "$3" "$B$2" | pretty > "$OUT/$1.json"; }
start() { c -H 'Content-Type: application/json' -d "$1" "$B/runtime/process-instances" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])'; }
failing() { echo "{\"processDefinitionKey\":\"$1\",\"variables\":[{\"name\":\"amount\",\"value\":100,\"type\":\"integer\"},{\"name\":\"divisor\",\"value\":0,\"type\":\"integer\"}]}"; }

for f in demo-failing-payment demo-failing-retry demo-order demo-parent demo-user-task; do
  c -F "file=@$P/$f.bpmn20.xml" "$B/repository/deployments" >/dev/null
done
FAIL=$(start "$(failing demoFailingPayment)")
start "$(failing demoFailingPayment)" >/dev/null
start "$(failing demoFailingRetry)" >/dev/null   # R10/PT1H: parks RETRYING in the timer table
start "$(failing demoParent)" >/dev/null         # call-activity child dead-letters
start '{"processDefinitionKey":"demoOrder"}' >/dev/null
UT=$(start '{"processDefinitionKey":"demoUserTask"}')

# The R1/PT1S retries dead-letter within seconds; bounded wait (60 x 1s).
for _ in $(seq 1 60); do
  n=$(c "$B/management/deadletter-jobs?size=1" | python3 -c 'import json,sys; print(json.load(sys.stdin)["total"])')
  [ "$n" -ge 3 ] && break
  sleep 1
done
[ "$n" -ge 3 ] || { echo "only $n dead-letter jobs after 60s" >&2; exit 1; }

recpost query-historic-process-instances /query/historic-process-instances '{"size":10,"sort":"startTime","order":"desc"}'
recpost query-historic-process-instances-first-2 /query/historic-process-instances '{"size":2,"sort":"startTime","order":"desc"}'
recpost query-historic-process-instances-next-2 /query/historic-process-instances '{"start":2,"size":2,"sort":"startTime","order":"desc"}'
recpost query-historic-process-instances-finished /query/historic-process-instances '{"finished":true,"size":1}'
recpost query-process-instances /query/process-instances '{"suspended":false,"size":1}'
recpost query-process-instances-suspended /query/process-instances '{"suspended":true,"size":1}'
rec deadletter-jobs "/management/deadletter-jobs?withException=true&start=0&size=50"
rec deadletter-jobs-first-2 "/management/deadletter-jobs?withException=true&start=0&size=2"
rec timer-jobs "/management/timer-jobs?withException=true&start=0&size=50"
rec jobs "/management/jobs?withException=true&start=0&size=50"
DLQ=$(field deadletter-jobs 'd["data"][0]["id"]')
TIMER=$(field timer-jobs 'd["data"][0]["id"]')
c "$B/management/deadletter-jobs/$DLQ/exception-stacktrace" > "$OUT/deadletter-exception-stacktrace.txt"
c "$B/management/timer-jobs/$TIMER/exception-stacktrace" > "$OUT/timer-exception-stacktrace.txt"
rec deadletter-job "/management/deadletter-jobs/$DLQ"
rec process-definitions-by-key "/repository/process-definitions?key=demoFailingPayment&size=50"
rec process-definitions-by-key-retry "/repository/process-definitions?key=demoFailingRetry&size=50"
DEF=$(field process-definitions-by-key 'd["data"][0]["id"]')
DEP=$(field process-definitions-by-key 'd["data"][0]["deploymentId"]')
rec process-definition "/repository/process-definitions/$DEF"
rec process-definition-model "/repository/process-definitions/$DEF/model"
c "$B/repository/process-definitions/$DEF/resourcedata" > "$OUT/process-definition-resourcedata.bpmn20.xml"
rec deployment "/repository/deployments/$DEP"
rec deployment-resources "/repository/deployments/$DEP/resources"
rec deployments "/repository/deployments?size=50"
rec historic-process-instance "/history/historic-process-instances/$FAIL"
rec runtime-process-instance "/runtime/process-instances/$FAIL"
rec historic-activity-instances "/history/historic-activity-instances?processInstanceId=$FAIL&size=50"
rec executions "/runtime/executions?processInstanceId=$FAIL&size=50"
EX=$(field executions '[e for e in d["data"] if e.get("parentId")][0]["id"]')
rec execution "/runtime/executions/$EX"
rec execution-local-variables "/runtime/executions/$EX/variables?scope=local"
rec variables "/runtime/process-instances/$FAIL/variables"
rec variable "/runtime/process-instances/$FAIL/variables/amount"
rec historic-variable-instances "/history/historic-variable-instances?processInstanceId=$FAIL&size=50"
rec event-subscriptions "/runtime/event-subscriptions?processInstanceId=$FAIL&size=50"
rec runtime-identity-links "/runtime/process-instances/$UT/identitylinks"
rec historic-identity-links "/history/historic-process-instances/$UT/identitylinks"
rec tasks "/runtime/tasks?processInstanceId=$UT&size=50"
TASK=$(field tasks 'd["data"][0]["id"]')
rec task "/runtime/tasks/$TASK"
rec task-identity-links "/runtime/tasks/$TASK/identitylinks"
rec historic-task-instances "/history/historic-task-instances?processInstanceId=$UT&size=50"
rec historic-task-instance "/history/historic-task-instances/$TASK"
rec engine "/management/engine"

# The engine's self-links carry the recording host; normalize so re-records diff cleanly.
sed -i "s#127\.0\.0\.1:$PORT#localhost:8080#g" "$OUT"/*.json
echo "recorded $(ls "$OUT" | wc -l) fixtures into $OUT"
