#!/usr/bin/env bash
# Offline selftest for scripts/hooks/*.sh (#644): temp git repo, stubbed mvn/npx.
set -u
here=$(cd "$(dirname "$0")" && pwd)
t=$(mktemp -d); trap 'rm -rf "$t"' EXIT
fail=0
ok() { # name expected-rc actual-rc
  if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: want rc=$2 got rc=$3"; fail=1; fi
}
printf '#!/bin/sh\necho "$@" >>"$STUB_LOG"\nexit "${STUB_RC:-0}"\n' >"$t/stub"; chmod +x "$t/stub"
cd "$t" && git init -q repo && cd repo
git config user.email t@t; git config user.name t
mkdir -p backend/src/main/java/a backend/src/test/java/a backend/src/main/resources/db/migration frontend/src/api frontend/scripts frontend/src/ui
for f in backend/src/main/java/a/X.java backend/src/test/java/a/YIT.java backend/src/main/resources/db/migration/V1__init.sql frontend/src/api/schema.d.ts frontend/scripts/check-bpmn-watermark.mjs frontend/src/ui/A.tsx; do echo x >"$f"; done
git add -A && git commit -qm init
export STUB_LOG="$t/log"; : >"$STUB_LOG"
pp() { printf '{"tool_name":"Edit","tool_input":{"file_path":"%s"}}' "$PWD/$1" | bash "$here/hooks/protected-paths.sh" >/dev/null 2>&1; echo $?; }
ok "pp schema.d.ts" 2 "$(pp frontend/src/api/schema.d.ts)"
ok "pp committed migration" 2 "$(pp backend/src/main/resources/db/migration/V1__init.sql)"
ok "pp watermark script" 2 "$(pp frontend/scripts/check-bpmn-watermark.mjs)"
ok "pp new migration" 0 "$(pp backend/src/main/resources/db/migration/V9__new.sql)"
ok "pp ordinary file" 0 "$(pp frontend/src/ui/A.tsx)"
echo '{bad' | bash "$here/hooks/protected-paths.sh" >/dev/null 2>&1; ok "pp bad json" 0 $?
run() { PI_STOP_HOOK_MVN="$t/stub" PI_STOP_HOOK_NPX="$t/stub" bash "$here/hooks/stop-tests.sh" >/dev/null 2>&1 <<<"${1:-{\}}"; echo $?; }
ok "stop clean tree" 0 "$(run)"
echo y >>backend/src/test/java/a/YIT.java
ok "stop IT only" 0 "$(run)"; [ ! -s "$STUB_LOG" ]; ok "stop IT only runs no mvn" 0 $?
echo y >>backend/src/main/java/a/X.java
ok "stop active" 0 "$(run '{"stop_hook_active":true}')"
ok "stop red" 2 "$(STUB_RC=1 run)"
grep -q -- '-Dtest=XTest' "$STUB_LOG"; ok "stop maps X -> XTest" 0 $?
STUB_RC=1 run >/dev/null; STUB_RC=1 run >/dev/null
ok "stop 3 reds already" 0 "$(STUB_RC=1 run)"
rm -f "$(git rev-parse --git-path pi-stop-hook-blocks)"
ok "stop green" 0 "$(run)"; : >"$STUB_LOG"
ok "stop marker fresh" 0 "$(STUB_RC=1 run)"; [ ! -s "$STUB_LOG" ]; ok "marker skips mvn" 0 $?
ok "stop mvn missing" 0 "$(PI_STOP_HOOK_MVN=/nonexistent bash "$here/hooks/stop-tests.sh" <<<'{}' >/dev/null 2>&1; echo $?)"
echo y >>frontend/src/ui/A.tsx; sleep 1; touch frontend/src/ui/A.tsx
: >"$STUB_LOG"; run >/dev/null; ! grep -q related "$STUB_LOG"; ok "no node_modules -> no vitest" 0 $?
mkdir frontend/node_modules; : >"$STUB_LOG"; run >/dev/null; grep -q 'vitest related --run frontend/src/ui/A.tsx\|vitest related --run src/ui/A.tsx' "$STUB_LOG"; ok "vitest related" 0 $?
echo y >"$t/repo/frontend/node_modules/.x"
exit $fail
