#!/usr/bin/env bash
# Read the newest green ci.yml run on main and print coverage totals as JSON.
# Best-effort: prints {} when the token, the run, or the artifacts are missing so the
# dashboard push never dies on this probe. History lives in the pusher, not here.
#
#   {"ciRunId":N,"nightlyRunId":N,"sha":"...","backendLine":74.2,"backendBranch":64.1,
#    "frontendLine":60.4,"frontendBranch":51.0}
set -uo pipefail

REPO="${PI_CI_REPO:-x3kcl/process-inspector}"
CACHE="${PI_COVERAGE_CACHE:-/tmp/pi-coverage-sample.json}"

if [[ -z "${GITHUB_PERSONAL_ACCESS_TOKEN:-}" ]]; then
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

ci_json="$(api "https://api.github.com/repos/$REPO/actions/workflows/ci.yml/runs?branch=main&status=success&per_page=1")"
ci_id="$(jq -r '.workflow_runs[0].id // empty' <<<"$ci_json" 2>/dev/null || true)"
sha="$(jq -r '.workflow_runs[0].head_sha // empty' <<<"$ci_json" 2>/dev/null || true)"
nightly_json="$(api "https://api.github.com/repos/$REPO/actions/workflows/nightly.yml/runs?branch=main&status=success&per_page=1")"
nightly_id="$(jq -r '.workflow_runs[0].id // empty' <<<"$nightly_json" 2>/dev/null || true)"

if [[ -z "$ci_id" ]]; then
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

arts="$(api "https://api.github.com/repos/$REPO/actions/runs/$ci_id/artifacts?per_page=100")"
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
  echo '{}'
  exit 0
fi
jq -nc \
  --argjson point "$point" \
  --argjson ci "$ci_id" \
  --arg sha "$sha" \
  --arg nightly "${nightly_id:-}" \
  '$point + {ciRunId: $ci, sha: $sha, nightlyRunId: (if $nightly == "" then null else ($nightly | tonumber) end)}' \
  | tee "$CACHE"
