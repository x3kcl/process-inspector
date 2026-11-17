#!/usr/bin/env bash
# Selftest for scripts/ci-coverage-attach.sh series handling (#463): frequent `ci` points must
# not evict the rarer `nightly` points, and the history file stays bounded. Offline: the sampler
# is stubbed in a throwaway copy of scripts/, history is a local file.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
FAIL=0
mkdir "$TMP/scripts"; cp "$ROOT/scripts/ci-coverage-attach.sh" "$TMP/scripts/"
cat >"$TMP/scripts/ci-coverage-sample.sh" <<'STUB'
#!/usr/bin/env bash
echo '{"ciRunId":999999,"nightlyRunId":888888,"backendLine":80.0,"frontendLine":60.0}'
STUB

# run NAME NCI NNIGHT -> history of NCI ci points + NNIGHT nightly points (nightly oldest), one more sample
run() {
  local name="$1" nci="$2" nn="$3" hist="$TMP/$1.json"
  jq -nc --argjson c "$nci" --argjson n "$nn" \
    '[range(0;$n)|{source:"nightly",at:(.+1),nightlyRunId:(.+1),backendLine:80}]
     + [range(0;$c)|{source:"ci",at:(1000+.),ciRunId:(1000+.),backendLine:80}]' >"$hist"
  echo '{}' >"$TMP/status.json"
  PI_COVERAGE_HIST_FILE="$hist" bash "$TMP/scripts/ci-coverage-attach.sh" "$TMP/status.json" 2>/dev/null
  jq -r '"\([.[]|select(.source=="ci")]|length) \([.[]|select(.source=="nightly")]|length)"' "$hist"
}
check() { # NAME GOT WANT
  if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: got '$2' want '$3'"; FAIL=1; fi
}

# 29 ci + 1 nightly + new ci + new nightly: nightly points survive, both new points added
check "ci points do not evict nightly points" "$(run evict 29 1)" "30 2"
# 60 ci + 40 nightly: each source bounded at 30
check "series bounded per source" "$(run bound 60 40)" "30 30"
exit "$FAIL"
