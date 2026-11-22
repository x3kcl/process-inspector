#!/usr/bin/env bash
# Selftest for scripts/check-compose-bind.sh: a compose file may publish a host port only on
# loopback (127.0.0.1 / [::1], or ${PI_BIND_ADDRESS:-127.0.0.1} — the documented LAN opt-in),
# except for the checker's small allowlist. Fixture cases first, the real tree last.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
FAIL=0
CHECK="$ROOT/scripts/check-compose-bind.sh"

# case NAME WANT_EXIT PORT_LINES... -> one compose file whose service publishes the given lines
case_() {
  local name="$1" want="$2"; shift 2
  local f="$TMP/$name.yml"
  { echo "services:"; echo "  web:"; echo "    image: nginx"; echo "    ports:"
    for l in "$@"; do echo "      $l"; done
    echo "    environment:"; echo "      - A=1"; } >"$f"
  bash "$CHECK" "$f" >"$TMP/$name.out" 2>&1
  local got=$?
  if [ "$got" = "$want" ]; then echo "ok   $name"; else echo "FAIL $name: exit $got want $want"; sed 's/^/     /' "$TMP/$name.out"; FAIL=1; fi
}

case_ loopback-v4          0 '- "127.0.0.1:9090:9090"'
case_ loopback-v6          0 "- '[::1]:9090:9090'"
case_ loopback-opt-in-var  0 '- "${PI_BIND_ADDRESS:-127.0.0.1}:${PI_ENGINE_A_PORT:-8081}:8080"'
case_ comment-and-unquoted 0 '# host port overridable' '- 127.0.0.1:3000:3000 # grafana'
case_ all-interfaces       1 '- "8080:80"'
case_ var-port-unbound     1 '- "${PI_HTTP_PORT:-8080}:80"'
case_ explicit-any         1 '- "0.0.0.0:80:80"'
case_ container-only       1 '- "5432"'
case_ var-defaults-to-any  1 '- "${PI_BIND_ADDRESS:-0.0.0.0}:80:80"'
case_ one-bad-of-two       1 '- "127.0.0.1:1:1"' '- "2:2"'
case_ long-syntax          1 '- target: 80' '  published: 8080'

# inline flow list is not parsed, so it is refused rather than waved through
printf 'services:\n  web:\n    ports: ["8080:80"]\n' >"$TMP/inline.yml"
if bash "$CHECK" "$TMP/inline.yml" >/dev/null 2>&1; then echo "FAIL inline-list: exit 0 want 1"; FAIL=1; else echo "ok   inline-list"; fi

# a file without ports passes; the finding names the file and the line
printf 'services:\n  web:\n    image: nginx\n' >"$TMP/none.yml"
if bash "$CHECK" "$TMP/none.yml" >/dev/null 2>&1; then echo "ok   no-ports"; else echo "FAIL no-ports: exit 1 want 0"; FAIL=1; fi
grep -q "all-interfaces.yml:5" "$TMP/all-interfaces.out" && echo "ok   finding-names-file-line" \
  || { echo "FAIL finding-names-file-line: $(cat "$TMP/all-interfaces.out")"; FAIL=1; }

# the real tree: every tracked compose file
if (cd "$ROOT" && bash "$CHECK"); then echo "ok   real-tree"; else echo "FAIL real-tree"; FAIL=1; fi
exit "$FAIL"
