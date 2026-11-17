#!/usr/bin/env bash
# [#644] Stop: run the tests this turn touched; exit 2 keeps the turn open on red.
# stop_hook_active, nothing touched, a fresh marker, three reds already, or a failure of
# the hook itself (no mvn, bad JSON) all exit 0.
set -u
input=$(cat || true)
active=$(printf '%s' "$input" | python3 -c '
import json, sys
try: print("1" if json.loads(sys.stdin.read() or "{}").get("stop_hook_active") else "0")
except Exception: print("0")' 2>/dev/null || echo 0)
[ "$active" = "1" ] && exit 0
repo=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
cd "$repo" || exit 0
changed=$({ git diff --name-only HEAD; git ls-files --others --exclude-standard; } 2>/dev/null | sort -u)
[ -n "$changed" ] || exit 0
names=$(printf '%s\n' "$changed" | python3 -c '
import sys
out = []
for raw in sys.stdin:
    p = raw.strip()
    base = p.rsplit("/", 1)[-1]
    if p.startswith("backend/src/main/java/") and base.endswith(".java"):
        n = base[:-5] + "Test"
    elif p.startswith("backend/src/test/java/") and base.endswith("Test.java"):
        n = base[:-5]
    else:
        continue
    if n not in out: out.append(n)
print(",".join(out))' 2>/dev/null || true)
fe=$(printf '%s\n' "$changed" | grep '^frontend/src/' | sed 's|^frontend/||' || true)
[ -n "$names" ] || { [ -n "$fe" ] && [ -d frontend/node_modules ]; } || exit 0

marker=$(git rev-parse --git-path pi-stop-hook-ran)
blocks=$(git rev-parse --git-path pi-stop-hook-blocks)
if [ -f "$marker" ]; then
  stale=0
  while IFS= read -r f; do
    [ -e "$f" ] && [ "$f" -nt "$marker" ] && { stale=1; break; }
  done <<<"$changed"
  [ "$stale" = 0 ] && exit 0
fi
n=0; [ -f "$blocks" ] && n=$(wc -l <"$blocks" | tr -d ' ')
if [ "${n:-0}" -ge 3 ]; then
  echo "stop-tests: warning — 3 red blocks already; not blocking again (counter reset)" >&2
  rm -f "$blocks"; exit 0
fi

log=$(mktemp); red=0
if [ -n "$names" ]; then
  mvn=${PI_STOP_HOOK_MVN:-mvn}
  (cd backend && "$mvn" -o -q test -Dtest="$names" -Djacoco.skip=true -Dsurefire.failIfNoSpecifiedTests=false) >"$log" 2>&1
  rc=$?
  [ "$rc" -eq 127 ] && { rm -f "$log"; exit 0; }
  [ "$rc" -ne 0 ] && red=1
fi
if [ "$red" = 0 ] && [ -n "$fe" ] && [ -d frontend/node_modules ]; then
  npx=${PI_STOP_HOOK_NPX:-npx}
  # shellcheck disable=SC2086
  (cd frontend && "$npx" vitest related --run $fe) >"$log" 2>&1
  rc=$?
  [ "$rc" -eq 127 ] && { rm -f "$log"; exit 0; }
  [ "$rc" -ne 0 ] && red=1
fi
if [ "$red" = 1 ]; then
  echo red >>"$blocks"
  tail -n 40 "$log" >&2
  rm -f "$log"; exit 2
fi
rm -f "$log" "$blocks"; touch "$marker"; exit 0
