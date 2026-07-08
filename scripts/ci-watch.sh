#!/usr/bin/env bash
#
# ci-watch.sh — block until the GitHub Actions run for a commit finishes, then
# report per-job / per-step status and DUMP the log of every failing step.
#
# This is the feedback-loop primitive behind the `green-ci` skill: a commit to
# main is not "done" until this script exits 0. There is no `gh` CLI on this box,
# so we talk to the Actions REST API directly with curl + $GITHUB_PERSONAL_ACCESS_TOKEN.
#
#   scripts/ci-watch.sh [SHA] [--timeout SECONDS] [--interval SECONDS] [--quiet]
#
#   SHA          commit to watch (default: current HEAD)
#   --timeout    give up waiting after N seconds (default: 1500 = 25m; the
#                integration matrix is capped at 20m)
#   --interval   poll cadence in seconds (default: 20)
#   --quiet      only print the final verdict + failing logs (no live ticks)
#
# Exit codes:
#   0  run completed and every job succeeded (or was skipped/neutral)
#   1  run completed with at least one failing/cancelled/timed-out job
#   2  no run found for the SHA within the grace window, or a hard error
#   3  timed out waiting for the run to complete
#
# Secrets: the token is read from the environment and never echoed.
set -euo pipefail

SHA=""
TIMEOUT=1500
INTERVAL=20
QUIET=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --timeout) TIMEOUT="$2"; shift 2 ;;
    --interval) INTERVAL="$2"; shift 2 ;;
    --quiet) QUIET=1; shift ;;
    -*) echo "unknown flag: $1" >&2; exit 2 ;;
    *) SHA="$1"; shift ;;
  esac
done

: "${GITHUB_PERSONAL_ACCESS_TOKEN:?set GITHUB_PERSONAL_ACCESS_TOKEN to watch CI}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ -n "$SHA" ]] || SHA="HEAD"
# The Actions ?head_sha= filter matches only FULL 40-char SHAs — expand a short SHA / ref.
SHA="$(git -C "$ROOT" rev-parse "$SHA")" || { echo "ci-watch: cannot resolve SHA '$SHA'" >&2; exit 2; }

# owner/repo from the origin remote (git@github.com:owner/repo.git or https form)
REMOTE="$(git -C "$ROOT" remote get-url origin)"
SLUG="$(printf '%s' "$REMOTE" | sed -E 's#^.*github\.com[:/]##; s#\.git$##')"
API="https://api.github.com/repos/${SLUG}"

gh_get() {
  curl -fsSL \
    -H "Authorization: Bearer ${GITHUB_PERSONAL_ACCESS_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "$@"
}

log() { [[ "$QUIET" == 1 ]] || echo "$@" >&2; }

log "ci-watch: ${SLUG} @ ${SHA:0:8} (timeout ${TIMEOUT}s, poll ${INTERVAL}s)"

# ---- 1. find the run id for this SHA (may lag a few seconds behind the push) ----
RUN_ID=""
find_deadline=$(( $(date +%s) + 90 ))
while :; do
  RUN_ID="$(gh_get "${API}/actions/runs?head_sha=${SHA}&per_page=1" \
    | python3 -c 'import sys,json; r=json.load(sys.stdin)["workflow_runs"]; print(r[0]["id"] if r else "")')"
  [[ -n "$RUN_ID" ]] && break
  if (( $(date +%s) >= find_deadline )); then
    echo "ci-watch: no workflow run found for ${SHA:0:8} within 90s" >&2
    exit 2
  fi
  sleep 5
done
log "ci-watch: run https://github.com/${SLUG}/actions/runs/${RUN_ID}"

# ---- 2. poll until the run completes (or we time out) ----
watch_deadline=$(( $(date +%s) + TIMEOUT ))
STATUS="" CONCLUSION=""
while :; do
  read -r STATUS CONCLUSION < <(gh_get "${API}/actions/runs/${RUN_ID}" \
    | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["status"], d["conclusion"] or "-")')

  if [[ "$STATUS" == "completed" ]]; then
    break
  fi

  # live per-job snapshot
  if [[ "$QUIET" != 1 ]]; then
    gh_get "${API}/actions/runs/${RUN_ID}/jobs?per_page=50" | python3 -c '
import sys,json
for j in json.load(sys.stdin)["jobs"]:
    mark = {"success":"ok","failure":"XX","cancelled":"--","skipped":"..","":".."}.get(j["conclusion"] or "", "..")
    status = j["status"]; name = j["name"]
    print(f"    [{mark}] {status:<11} {name}")
' >&2
  fi

  if (( $(date +%s) >= watch_deadline )); then
    echo "ci-watch: TIMEOUT after ${TIMEOUT}s (run still ${STATUS})" >&2
    exit 3
  fi
  sleep "$INTERVAL"
done

# ---- 3. final report ----
echo "" >&2
echo "ci-watch: run ${CONCLUSION^^} — https://github.com/${SLUG}/actions/runs/${RUN_ID}" >&2

JOBS_JSON="$(gh_get "${API}/actions/runs/${RUN_ID}/jobs?per_page=50")"
printf '%s' "$JOBS_JSON" | python3 -c '
import sys,json
for j in json.load(sys.stdin)["jobs"]:
    mark = {"success":"ok","failure":"XX","cancelled":"--","skipped":".."}.get(j["conclusion"] or "", "..")
    name = j["name"]
    print(f"    [{mark}] {name}")
    if j["conclusion"] == "failure":
        for s in j["steps"]:
            if s["conclusion"] == "failure":
                sname = s["name"]
                print(f"          [FAILED STEP] {sname}")
' >&2

# dump the log of each failing job (tail only — full logs are large)
FAILED_IDS="$(printf '%s' "$JOBS_JSON" | python3 -c '
import sys,json
print("\n".join(str(j["id"]) for j in json.load(sys.stdin)["jobs"] if j["conclusion"]=="failure"))')"

if [[ -n "$FAILED_IDS" ]]; then
  echo "" >&2
  echo "========================= FAILING JOB LOGS (tail) =========================" >&2
  while read -r jid; do
    [[ -n "$jid" ]] || continue
    echo "----- job ${jid} -----" >&2
    # /logs 302-redirects to a plaintext blob; -L follows it.
    gh_get "${API}/actions/jobs/${jid}/logs" 2>/dev/null | grep -aE '(FAIL|ERROR|error|Error|\[warn\]|Exception|BUILD FAILURE|##\[error\])' | tail -40 >&2 || true
    echo "" >&2
  done <<< "$FAILED_IDS"
fi

[[ "$CONCLUSION" == "success" ]] && exit 0
exit 1
