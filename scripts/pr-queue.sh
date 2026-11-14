#!/usr/bin/env bash
#
# pr-queue.sh — land a pull request through the ci-lib merge queue.
#
#   scripts/pr-queue.sh <PR>            refuse unless the head's `ci` run is green, then add the `queue` label
#   scripts/pr-queue.sh --watch <PR>    block until merged (0), unlabelled by the queue (1, prints its comment), timeout (3)
#   scripts/pr-queue.sh --status <PR>   print label, head run, merged flag
#
# Exit: 0 ok/merged, 1 head not green / queue dropped the PR, 2 usage or API error, 3 watch timeout.
# curl + $GITHUB_PERSONAL_ACCESS_TOKEN (never echoed). Env: PQ_SLUG, PQ_INTERVAL (30), PQ_TIMEOUT (3600).
set -euo pipefail

MODE=label
case "${1:-}" in --watch) MODE=watch; shift ;; --status) MODE=status; shift ;; esac
PR="${1:?usage: pr-queue.sh [--watch|--status] <PR>}"
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
# head run: "<status>/<conclusion>" of the ci.yml run on a sha
head_run() {
  api "${API}/actions/runs?head_sha=$1&per_page=50" | jget \
    '(lambda r: (r[0]["status"]+"/"+(r[0]["conclusion"] or "-")) if r else "none")([x for x in d["workflow_runs"] if x["path"].endswith("/ci.yml")])'
}

ST="$(state)" || apierr
read -r SHA MERGED LABELLED <<<"$ST"

if [[ "$MODE" == status ]]; then
  RUN="$(head_run "$SHA")" || apierr
  echo "pr #${PR}: label=${LABELLED} head=${SHA:0:8} run=${RUN} merged=${MERGED}"
  exit 0
fi

if [[ "$MODE" == label ]]; then
  RUN="$(head_run "$SHA")" || apierr
  if [[ "$RUN" != completed/success ]]; then
    echo "pr-queue: head ${SHA:0:8} ci run is '${RUN}', not green; fix it first" >&2
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
  (( $(date +%s) < deadline )) || { echo "pr-queue: timeout waiting for #${PR}" >&2; exit 3; }
  sleep "${PQ_INTERVAL:-30}"
done
