#!/usr/bin/env bash
# Read the newest green ci.yml run that still has coverage artifacts and print coverage totals as JSON.
# Best-effort: prints {} when the token, the run, or the artifacts are missing so the
# dashboard push never dies on this probe. History lives in the pusher, not here.
#
#   {"ciRunId":N,"nightlyRunId":N,"sha":"...","backendLine":74.2,"backendBranch":64.1,
#    "frontendLine":60.4,"frontendBranch":51.0}
set -uo pipefail

REPO="${PI_CI_REPO:-x3kcl/process-inspector}"
CACHE="${PI_COVERAGE_CACHE:-/tmp/pi-coverage-sample.json}"

if [[ -z "${GITHUB_PERSONAL_ACCESS_TOKEN:-}" ]]; then
  echo "coverage sample: NONE — GITHUB_PERSONAL_ACCESS_TOKEN not set in this environment (cron)" >&2
  echo '{}'
  exit 0
fi

api() {
  curl -fsS --max-time 25 \
    -H "Authorization: Bearer $GITHUB_PERSONAL_ACCESS_TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "$1" 2>/dev/null || echo null
}

# GitHub's artifact zip endpoint 302s to a blob host that rejects the Authorization
# header. Capture the redirect and fetch it without the token.
download_zip() {
  local url="$1" dest="$2" loc
  loc="$(curl -fsS --max-time 20 -o /dev/null -w '%{redirect_url}' \
    -H "Authorization: Bearer $GITHUB_PERSONAL_ACCESS_TOKEN" \
    -H "Accept: application/vnd.github+json" \
    "$url" 2>/dev/null || true)"
  [[ -n "$loc" ]] || return 1
  curl -fsS --max-time 60 -L -o "$dest" "$loc"
}

# why <reason>: say loudly on stderr why no sample is produced (#463: the series stayed empty
# for weeks because the newest green main run had no artifacts and nothing said so).
why() { echo "coverage sample: NONE — $1" >&2; }
runs_url="https://api.github.com/repos/$REPO/actions/workflows/ci.yml/runs?status=success&per_page=50"
runs="$(api "$runs_url")"
if ! jq -e '.workflow_runs' >/dev/null 2>&1 <<<"$runs"; then
  why "GitHub API call failed (token scope or network): $runs_url"
  echo '{}'
  exit 0
fi
nightly_json="$(api "https://api.github.com/repos/$REPO/actions/workflows/nightly.yml/runs?branch=main&status=success&per_page=1")"
nightly_id="$(jq -r '.workflow_runs[0].id // empty' <<<"$nightly_json" 2>/dev/null || true)"

# ci.yml has no push-to-main trigger since the merge queue (c47aa5a): a batch runs on mq/*, then
# main is fast-forwarded, so a run "on main" is only the stale pre-queue history. Candidates:
# green push runs on main or mq/* first, then green pull_request runs; the first one whose
# jacoco-report AND frontend-coverage artifacts are still live (14-day retention) wins.
cands="$(jq -r '[.workflow_runs[]
    | select((.event == "push" and (.head_branch == "main" or (.head_branch | startswith("mq/")))))
    ] + [.workflow_runs[] | select(.event == "pull_request")]
  | .[] | "\(.id) \(.head_sha) \(.event)"' <<<"$runs")"
if [[ -z "$cands" ]]; then
  why "no green ci.yml run among the newest 50"
  echo '{}'
  exit 0
fi

ci_id="" sha="" ci_event="" arts=""
while read -r id hsha ev; do
  [[ -n "$id" ]] || continue
  a="$(api "https://api.github.com/repos/$REPO/actions/runs/$id/artifacts?per_page=100")"
  if [[ "$(jq '[.artifacts[]? | select((.name == "jacoco-report" or .name == "frontend-coverage") and (.expired | not))] | length' <<<"$a" 2>/dev/null)" == "2" ]]; then
    ci_id="$id" sha="$hsha" ci_event="$ev" arts="$a"
    break
  fi
done <<<"$cands"
if [[ -z "$ci_id" ]]; then
  why "none of $(wc -l <<<"$cands") green ci runs has live jacoco-report + frontend-coverage artifacts (expired, or not uploaded)"
  echo '{}'
  exit 0
fi

cached_id=""
if [[ -f "$CACHE" ]]; then
  cached_id="$(jq -r '.ciRunId // empty' "$CACHE" 2>/dev/null || true)"
fi

if [[ "$cached_id" == "$ci_id" && -f "$CACHE" ]]; then
  jq -c --arg n "${nightly_id:-}" '. + {nightlyRunId: (if $n == "" then null else ($n | tonumber) end)}' "$CACHE"
  exit 0
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

parse() {
  python3 - "$tmp" <<'PY'
import json, sys, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
root = Path(sys.argv[1])
out = {}
jacoco = root / "jacoco.xml"
if jacoco.exists():
    report = ET.parse(jacoco).getroot()
    for counter in report.findall("counter"):
        missed = int(counter.get("missed") or 0)
        covered = int(counter.get("covered") or 0)
        total = missed + covered
        if total == 0:
            continue
        pct = round(100 * covered / total, 1)
        if counter.get("type") == "LINE":
            out["backendLine"] = pct
        elif counter.get("type") == "BRANCH":
            out["backendBranch"] = pct
summary = root / "coverage-summary.json"
if summary.exists():
    total = json.loads(summary.read_text())["total"]
    out["frontendLine"] = total["lines"]["pct"]
    out["frontendBranch"] = total["branches"]["pct"]
print(json.dumps(out))
PY
}

grab() {
  local name="$1" id
  id="$(jq -r --arg n "$name" '.artifacts[]? | select(.name == $n) | .id' <<<"$arts" 2>/dev/null | head -1 || true)"
  [[ -n "$id" ]] || return 0
  download_zip "https://api.github.com/repos/$REPO/actions/artifacts/$id/zip" "$tmp/$name.zip" || return 0
  unzip -qo "$tmp/$name.zip" -d "$tmp"
}

grab jacoco-report
grab frontend-coverage
point="$(parse)"
if [[ "$point" == "{}" || -z "$point" ]]; then
  why "run $ci_id artifacts downloaded/parsed to nothing (download redirect or zip layout)"
  echo '{}'
  exit 0
fi
jq -nc \
  --argjson point "$point" \
  --argjson ci "$ci_id" \
  --arg sha "$sha" \
  --arg ev "$ci_event" \
  --arg nightly "${nightly_id:-}" \
  '$point + {ciRunId: $ci, sha: $sha, ciEvent: $ev, nightlyRunId: (if $nightly == "" then null else ($nightly | tonumber) end)}' \
  | tee "$CACHE"
