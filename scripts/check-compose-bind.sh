#!/usr/bin/env bash
# check-compose-bind.sh [compose-file...] — every host port a compose file publishes binds
# loopback: "127.0.0.1:…", "[::1]:…", or "${PI_BIND_ADDRESS:-127.0.0.1}:…" (the documented opt-in
# for LAN access: PI_BIND_ADDRESS=0.0.0.0). A bare "8080:80", a container-only "5432" (random
# host port on every interface), the long syntax and inline lists are findings. Without
# arguments it checks every tracked compose file. Selftest: scripts/compose-bind-selftest.sh.
set -uo pipefail

# file|port-entry pairs that may bind all interfaces, each with its reason.
ALLOW=(
  # The CI dashboard exists to be opened from the operator's browser; static CI telemetry only.
  'deploy/ci-dashboard/docker-compose.yml|"${DASH_PORT:-8091}:8091"'
)

if [ "$#" -eq 0 ]; then
  cd "$(git rev-parse --show-toplevel)"
  mapfile -t files < <(git ls-files | grep -E '(^|/)(docker-)?compose[^/]*\.ya?ml$')
else
  files=("$@")
fi

fail=0
for f in "${files[@]}"; do
  while IFS='|' read -r line entry; do
    allowed=0
    for a in "${ALLOW[@]}"; do [ "$a" = "$f|$entry" ] && allowed=1; done
    [ "$allowed" = 1 ] && continue
    echo "check-compose-bind: $f:$line publishes $entry without a loopback bind address" >&2
    fail=1
  done < <(awk '
    function indent(s) { match(s, /^ */); return RLENGTH }
    /^[[:space:]]*(#|$)/ { next }
    inports && (indent($0) < pind || (indent($0) == pind && $0 !~ /^[[:space:]]*-/)) { inports = 0 }
    inports {
      v = $0; sub(/^[[:space:]]*-[[:space:]]*/, "", v); sub(/[[:space:]]+#.*$/, "", v)
      raw = v; gsub(/["\047]/, "", v)
      if (v !~ /^(127\.0\.0\.1|\[::1\]|\$\{PI_BIND_ADDRESS:-127\.0\.0\.1\}):/) print NR "|" raw
      next
    }
    /^[[:space:]]*ports:/ {
      if ($0 ~ /ports:[[:space:]]*[^[:space:]#]/) { print NR "|inline list (use one - entry per line)"; next }
      inports = 1; pind = indent($0)
    }' "$f")
done
exit "$fail"
