#!/usr/bin/env bash
#
# pr-queue.sh — land a pull request through the ci-lib merge queue.
#
#   scripts/pr-queue.sh <PR>            refuse unless EVERY pull_request run on the head is green, then add the `queue` label
#   scripts/pr-queue.sh --watch <PR>    (flag before or after <PR>) block until merged (0), unlabelled by the queue or refused with a queue refusal for the head (mq-verdict refused, mq-red, mq-red-first, mq-red-frozen, mq-skip) (1, prints its comment), timeout (3)
#   scripts/pr-queue.sh --status <PR>   print label, head run, merged flag, and "refused: <code or marker>" when the queue refused the head
#
# Exit: 0 ok/merged, 1 head not green / queue dropped or refused the PR, 2 usage or API error, 3 watch timeout.
# curl + $GITHUB_PERSONAL_ACCESS_TOKEN (never echoed). Env: PQ_SLUG, PQ_INTERVAL (30), PQ_TIMEOUT (3600).
set -euo pipefail

MODE=label PR=""
USAGE="usage: pr-queue.sh [--watch|--status] <PR>   (flag may come before or after the number)"
for a in "$@"; do
  case "$a" in
    --watch|--status) [[ $MODE == label ]] || { echo "$USAGE" >&2; exit 2; }; MODE="${a#--}" ;;
    ''|*[!0-9]*) echo "$USAGE" >&2; exit 2 ;;
    *) [[ -z "$PR" ]] || { echo "$USAGE" >&2; exit 2; }; PR="$a" ;;
  esac
done
[[ -n "$PR" ]] || { echo "$USAGE" >&2; exit 2; }
: "${GITHUB_PERSONAL_ACCESS_TOKEN:?set GITHUB_PERSONAL_ACCESS_TOKEN}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SLUG="${PQ_SLUG:-$(git -C "$ROOT" remote get-url origin | sed -E 's#^.*github\.com[:/]##; s#\.git$##')}"
API="https://api.github.com/repos/${SLUG}"
api() {
  curl -fsSL -H "Authorization: Bearer ${GITHUB_PERSONAL_ACCESS_TOKEN}" \
    -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" "$@"
}
jget() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }
# an unreadable API answer is exit 2, never 1 (1 means: not green / dropped by the queue)
apierr() { echo "pr-queue: GitHub API error for #${PR}" >&2; exit 2; }

# state: prints "<sha> <merged> <labelled>"
state() {
  api "${API}/pulls/${PR}" | jget 'd["head"]["sha"], str(d["merged"]).lower(), str(any(l["name"]=="queue" for l in d["labels"])).lower()'
}
# refusal: the queue keeps the label on a per-head refusal and comments once per head (push a new head). Prints "<tag>" then the
# comment (nothing if none). Refusals: mq-verdict JSON with state=refused for the head (tag = its code), else the markers
# mq-red / mq-red-first / mq-red-frozen / mq-skip : <head> (ci-lib merge_queue.py).
refusal() {
  api "${API}/issues/${PR}/comments?per_page=100" | SHA="$1" python3 -c "
import sys,json,os,re
sha=os.environ['SHA']
m=re.compile(r'mq-(?:red(?:-first|-frozen)?|skip):'+sha+r'(?![0-9a-f])')
v=re.compile(r'mq-verdict (\{.*?\}) -->',re.S)
hit=None
for x in json.load(sys.stdin):
  b=x['body']; t=None
  for j in v.findall(b):
    try: d=json.loads(j)
    except ValueError: continue
    if d.get('head')==sha and d.get('state')=='refused': t=d.get('code','refused')
  mm=m.search(b)
  t=t or (mm.group(0) if mm else None)
  if t: hit=(t,b)
if hit: print(hit[0]); print(hit[1],end='')"
}
# head run: every pull_request-event run on a sha, newest per workflow (an unreadable event counts, as in the queue).
# Prints "completed/success" when all are green, else "<workflow> <status>/<conclusion>" of the first that is not; "none" when no run
head_run() {
  api "${API}/actions/runs?head_sha=$1&per_page=100" | python3 -c "
import sys,json
n={}
for x in sorted(json.load(sys.stdin)['workflow_runs'],key=lambda x:x.get('id',0)):
  if (x.get('event') or 'pull_request').startswith('pull_request'):
    n[x['path']]=(x.get('name') or x['path'].rsplit('/',1)[-1], x['status']+'/'+(x['conclusion'] or '-'))
bad=[w+' '+s for w,s in sorted(n.values()) if s!='completed/success']
print(bad[0] if bad else 'completed/success' if n else 'none')"
}

ST="$(state)" || apierr
read -r SHA MERGED LABELLED <<<"$ST"

if [[ "$MODE" == status ]]; then
  RUN="$(head_run "$SHA")" || apierr
  echo "pr #${PR}: label=${LABELLED} head=${SHA:0:8} run=${RUN} merged=${MERGED}"
  if [[ "$LABELLED" == true ]]; then
    REF="$(refusal "$SHA")" || apierr
    [[ -z "$REF" ]] || echo "refused: $(head -1 <<<"$REF")"
  fi
  exit 0
fi

if [[ "$MODE" == label ]]; then
  RUN="$(head_run "$SHA")" || apierr
  if [[ "$RUN" != completed/success ]]; then
    echo "pr-queue: head ${SHA:0:8} pull_request run is '${RUN}', not green yet; fix it first" >&2
    exit 1
  fi
  [[ "$LABELLED" == true ]] || api -X POST "${API}/issues/${PR}/labels" -d '{"labels":["queue"]}' >/dev/null || apierr
  echo "pr-queue: #${PR} labelled queue (head ${SHA:0:8})"
  exit 0
fi

deadline=$(( $(date +%s) + ${PQ_TIMEOUT:-3600} ))
while :; do
  ST="$(state)" || apierr
  read -r SHA MERGED LABELLED <<<"$ST"
  [[ "$MERGED" == true ]] && { echo "pr-queue: #${PR} merged"; exit 0; }
  if [[ "$LABELLED" != true ]]; then
    echo "pr-queue: #${PR} lost the queue label; last comment:" >&2
    api "${API}/issues/${PR}/comments?per_page=100" | jget 'd[-1]["body"] if d else "(none)"' >&2
    exit 1
  fi
  REF="$(refusal "$SHA")" || apierr
  if [[ -n "$REF" ]]; then
    echo "pr-queue: #${PR} refused by the queue (label kept; push a new head):" >&2
    tail -n +2 <<<"$REF"
    exit 1
  fi
  (( $(date +%s) < deadline )) || { echo "pr-queue: timeout waiting for #${PR}" >&2; exit 3; }
  sleep "${PQ_INTERVAL:-30}"
done
