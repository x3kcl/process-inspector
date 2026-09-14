#!/usr/bin/env bash
# Seed the LIVE DEMO (pi.naumann.cloud) with the extra instance population the feature
# videos need. REST-only — never touches engine tables (CLAUDE.md iron rule).
#
# The demo engines sit on the compose `internal` network with no host ports, so every call
# goes through a throwaway curl container attached to that network by DNS alias.
#
# What the demo already had (2026-09-14) and what this adds:
#   - 435 ACTIVE / 48 FAILED / 3 live error classes / 6 ledger incidents — already there.
#   - ZERO completed `demoFailingPayment` instances, so the instance-detail **Compare** tab
#     had no successful sibling to suggest. -> PAY-OK-* below.
#   - No cleanly-identifiable failed instance to use as the on-camera surgery target.
#     -> PAY-FIX-* below (divisor=0; the documented recovery arc is: edit divisor to a
#        non-zero value, retry the dead-letter job, instance completes).
#
# ⚠️ These rows land in the same pilot dataset the R1/R2 data-maturity gates mine. Every
# instance carries a business key prefixed `PAY-OK-` / `PAY-FIX-`, and the run stamps its
# window to presentation/DEMO-SEED-WINDOW.md, so the footage population can be excluded
# from a later gate measurement.
set -euo pipefail

NET=process-inspector-demo_internal
CRED=rest-admin:test
HERE="$(cd "$(dirname "$0")" && pwd)"

R() { docker run --rm --network "$NET" curlimages/curl:latest -s -u "$CRED" "$@"; }
svc() { echo "http://$1:8080/flowable-rest/service"; }

start() { # engine-alias json -> instance id
  R -H 'Content-Type: application/json' -d "$2" "$(svc "$1")/runtime/process-instances" \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])'
}

started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
stamp="$(date +%s)"
echo "seed window opened $started_at"

# --- 1. Successful siblings -------------------------------------------------------------
# divisor=2 -> ${amount % divisor} evaluates, the async task clears, the instance COMPLETES.
# Varying `amount` gives the Compare tab a real variable diff to render, not a trivial one.
for pair in "100 2" "250 5" "980 7" "1200 3" "640 4" "75 5"; do
  read -r amt div <<<"$pair"
  id=$(start engine-a "{\"processDefinitionKey\":\"demoFailingPayment\",\"businessKey\":\"PAY-OK-$amt-$stamp\",\"variables\":[
    {\"name\":\"amount\",\"type\":\"integer\",\"value\":$amt},
    {\"name\":\"divisor\",\"type\":\"integer\",\"value\":$div},
    {\"name\":\"customer\",\"type\":\"string\",\"value\":\"ACME Industries\"},
    {\"name\":\"channel\",\"type\":\"string\",\"value\":\"card\"}]}")
  echo "  engine-a demoFailingPayment $id  PAY-OK-$amt (amount=$amt divisor=$div -> completes)"
done

# --- 2. On-camera surgery targets ------------------------------------------------------
# divisor=0 -> ArithmeticException -> R1/PT1S -> dead-letters organically within ~2s.
# Same variable shape as the successful siblings above so Compare has something to say.
for amt in 100 250 980; do
  id=$(start engine-a "{\"processDefinitionKey\":\"demoFailingPayment\",\"businessKey\":\"PAY-FIX-$amt-$stamp\",\"variables\":[
    {\"name\":\"amount\",\"type\":\"integer\",\"value\":$amt},
    {\"name\":\"divisor\",\"type\":\"integer\",\"value\":0},
    {\"name\":\"customer\",\"type\":\"string\",\"value\":\"ACME Industries\"},
    {\"name\":\"channel\",\"type\":\"string\",\"value\":\"card\"}]}")
  echo "  engine-a demoFailingPayment $id  PAY-FIX-$amt (divisor=0 -> dead-letters)"
done

# One on engine-b as well, so the grid clip shows the same class on two engines.
id=$(start engine-b "{\"processDefinitionKey\":\"demoFailingPayment\",\"businessKey\":\"PAY-FIX-440-$stamp\",\"variables\":[
  {\"name\":\"amount\",\"type\":\"integer\",\"value\":440},
  {\"name\":\"divisor\",\"type\":\"integer\",\"value\":0},
  {\"name\":\"customer\",\"type\":\"string\",\"value\":\"Globex\"},
  {\"name\":\"channel\",\"type\":\"string\",\"value\":\"sepa\"}]}")
echo "  engine-b demoFailingPayment $id  PAY-FIX-440 (divisor=0 -> dead-letters)"

echo "waiting ~12s for the async jobs to run their R1/PT1S cycle and settle ..."
sleep 12

# The recorder addresses instances by composite id, so hand it the ids this run created.
R "$(svc engine-a)/runtime/process-instances?processDefinitionKey=demoFailingPayment&size=200" \
  | python3 -c "
import sys, json
d = json.load(sys.stdin)
out = {}
for x in d['data']:
    bk = x.get('businessKey') or ''
    for tag in ('PAY-FIX-100', 'PAY-FIX-250', 'PAY-FIX-980'):
        if bk.startswith(tag):
            out[tag] = {'engine': 'engine-a', 'id': x['id'], 'businessKey': bk}
json.dump(out, open('$HERE/.video-targets.json', 'w'), indent=1)
print('  wrote .video-targets.json with', len(out), 'surgery targets')
"

ended_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
{
  echo "# Demo seed window — presentation footage"
  echo
  echo "Instances created on the live demo purely to record the feature videos."
  echo "**Exclude this window from R1/R2 data-maturity gate measurements** (ALARM-COST-MODEL.md §7,"
  echo "RETRYING-RISK-LANE.md §7.2) and from any audit-log mining of operator behaviour: the"
  echo "corrective actions inside it were performed by a recorder, not by an operator."
  echo
  echo "| | |"
  echo "|---|---|"
  echo "| opened | \`$started_at\` |"
  echo "| closed | \`$ended_at\` |"
  echo "| engines | engine-a, engine-b |"
  echo "| definition | \`demoFailingPayment\` |"
  echo "| business keys | \`PAY-OK-%\` (successful siblings) · \`PAY-FIX-%\` (surgery targets) |"
  echo
  echo "Regenerate with \`presentation/seed-demo-for-video.sh\`."
} > "$HERE/DEMO-SEED-WINDOW.md"
echo "seed window closed $ended_at -> $HERE/DEMO-SEED-WINDOW.md"

echo
echo "verification:"
for e in engine-a engine-b; do
  fin=$(R "$(svc $e)/history/historic-process-instances?processDefinitionKey=demoFailingPayment&finished=true&size=1" \
        | python3 -c 'import sys,json;print(json.load(sys.stdin)["total"])')
  dlq=$(R "$(svc $e)/management/deadletter-jobs?size=1" \
        | python3 -c 'import sys,json;print(json.load(sys.stdin)["total"])')
  echo "  $e: completed demoFailingPayment=$fin  dead-letter jobs=$dlq"
done
