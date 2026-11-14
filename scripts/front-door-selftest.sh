#!/usr/bin/env bash
# Selftest for scripts/front-door-check.sh (lr-1/lr-2/lr-3) and scripts/red-set.sh (#645).
# Each case builds a throwaway git repo, plants commits, and checks the exit code and the
# rule named in the output. Offline; no network, no mvn/npm.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="$ROOT/scripts/front-door-check.sh"
REDSET="$ROOT/scripts/red-set.sh"
TMP="$(mktemp -d)"
FAIL=0 N=0
# the verdict runs on exit, so cases a later RED commit appends below still count
trap 'rm -rf "$TMP"; [ "$FAIL" = 0 ] && echo "front-door-selftest: all ok" || echo "front-door-selftest: FAILED"; exit "$FAIL"' EXIT

g() { git -c user.name=t -c user.email=t@t -c commit.gpgsign=false "$@"; }
# new repo with one base commit; prints nothing, cds into it
repo() {
  N=$((N + 1)); mkdir "$TMP/r$N" && cd "$TMP/r$N" || { FAIL=1; exit; }
  g init -q && echo base >README && g add -A && g commit -qm base
}
# put FILE CONTENT -> write it (dirs created)
put() { mkdir -p "$(dirname "$1")" && printf '%b' "$2" >"$1"; }
# commit MESSAGE [FILE CONTENT]... -> write each file, commit everything
commit() {
  local msg="$1"; shift
  while [ $# -ge 2 ]; do put "$1" "$2"; shift 2; done
  g add -A && g commit -qm "$msg"
}
# expect NAME RC PATTERN [ARGS...] -> run the check on base..HEAD, assert rc and output
expect() {
  local name="$1" want="$2" pat="$3" out rc; shift 3
  out="$(bash "$CHECK" "$(git rev-list --max-parents=0 HEAD)" HEAD "$@" 2>&1)"; rc=$?
  if [ "$rc" != "$want" ] || ! grep -q -- "$pat" <<<"$out"; then
    echo "FAIL $name: rc=$rc want=$want, missing /$pat/"; sed 's/^/    /' <<<"$out"; FAIL=1
  else echo "ok   $name"; fi
}

T=backend/src/test/java/FooTest.java
M=backend/src/main/java/Foo.java

repo; commit "test(#1): RED — foo" $T 'a\n'; commit "feat: foo" $M 'x\n'
expect "lr-1 RED before code passes" 0 "rule: docs/landing-rules.md#lr-1"

repo; commit "feat: foo" $M 'x\n'
expect "lr-1 code without RED refused" 1 "✗ lr-1"

repo; commit "feat: foo" $M 'x\n'; commit "test(#1): RED — foo" $T 'a\n'
expect "lr-1 RED after code refused" 1 "✗ lr-1"

repo; commit "$(printf 'chore: bump\n\nNo-RED: dependency bump')" $M 'x\n'
expect "lr-1 No-RED trailer passes" 0 "lr-1 ok"

repo; commit "chore: tidy" scripts/x-selftest.sh 'echo\n'
expect "lr-1 test path wins over code path" 0 "lr-1 ok"

repo; commit "test(#1): RED — vitest" frontend/src/a/b.test.tsx 'a\n'; commit "feat: ui" frontend/src/a/b.tsx 'x\n'
expect "lr-1 nested vitest file is a test path" 0 "lr-1 ok"

repo; commit "ci: paths" ci/manifest.env 'CI_MQ_RED_CODE_PATHS="lib/"\n'
BASE_SHA="$(git rev-parse HEAD)"; commit "feat: foo" $M 'x\n'
out="$(bash "$CHECK" "$BASE_SHA" HEAD 2>&1)"; rc=$?
if [ "$rc" = 0 ]; then echo "ok   manifest code paths override defaults"
else echo "FAIL manifest code paths override defaults: rc=$rc"; sed 's/^/    /' <<<"$out"; FAIL=1; fi

repo; commit "test(#1): RED — foo" $T 'a\nb\n'; commit "feat: foo" $M 'x\n' $T 'a\nb\nc\n'
expect "lr-2 append in a non-RED commit refused" 1 "✗ lr-2"

repo; commit "test(#1): RED — foo" $T 'a\nb\n'; commit "test(#1): RED — more" $T 'a\nb\nc\n'; commit "feat: foo" $M 'x\n'
expect "lr-2 append in a later RED commit passes" 0 "lr-2 ok"

repo; commit "test(#1): RED — foo" $T 'a\nb\n'; commit "test(#1): RED — more" $T 'a\nB\nc\n'; commit "feat: foo" $M 'x\n'
expect "lr-2 later RED editing an earlier case refused" 1 "✗ lr-2"

repo; commit "test(#1): RED — foo" $T 'a\nb\n'; commit "feat: foo" $M 'x\n' $T 'a\nB\n'
expect "lr-2 edited RED line refused" 1 "✗ lr-2"

repo; commit "test(#1): RED — foo" $T 'a\nb\n'; g rm -q $T && g commit -qm "feat: drop"
expect "lr-2 deleted RED file refused" 1 "✗ lr-2"

repo; commit "test(#2): add" $T 'assertThat(x).isEqualTo(1);\n'
BASE_SHA="$(git rev-parse HEAD)"; commit "test(#2): loosen" $T 'assertThat(x).isNotNull();\n'
out="$(bash "$CHECK" "$BASE_SHA" HEAD 2>&1)"; rc=$?
if [ "$rc" = 1 ] && grep -q "✗ lr-3" <<<"$out"; then echo "ok   lr-3 dropped strong matcher refused"
else echo "FAIL lr-3 dropped strong matcher refused: rc=$rc"; sed 's/^/    /' <<<"$out"; FAIL=1; fi
echo "Contract-change: $T — loosened on purpose — reviewed-by alice" >"$TMP/body"
out="$(bash "$CHECK" "$BASE_SHA" HEAD --body-file "$TMP/body" 2>&1)"; rc=$?
if [ "$rc" = 0 ]; then echo "ok   lr-3 Contract-change waiver passes"
else echo "FAIL lr-3 Contract-change waiver passes: rc=$rc"; sed 's/^/    /' <<<"$out"; FAIL=1; fi

repo; commit "test(#2): add" frontend/src/a.test.ts 'expect(x).toEqual(1);\n'
BASE_SHA="$(git rev-parse HEAD)"; commit "test(#2): swap" frontend/src/a.test.ts 'expect(x).toEqual(2);\n'
out="$(bash "$CHECK" "$BASE_SHA" HEAD 2>&1)"; rc=$?
if [ "$rc" = 0 ] && grep -q "lr-3 ok" <<<"$out"; then echo "ok   lr-3 strong-for-strong swap passes"
else echo "FAIL lr-3 strong-for-strong swap passes: rc=$rc"; sed 's/^/    /' <<<"$out"; FAIL=1; fi
commit "test(#2): weaken" frontend/src/a.test.ts 'expect(x).toBeDefined();\n'
out="$(bash "$CHECK" "$BASE_SHA" HEAD 2>&1)"; rc=$?
if [ "$rc" = 1 ] && grep -q "✗ lr-3" <<<"$out"; then echo "ok   lr-3 vitest toEqual -> toBeDefined refused"
else echo "FAIL lr-3 vitest toEqual -> toBeDefined refused: rc=$rc"; sed 's/^/    /' <<<"$out"; FAIL=1; fi

repo; commit "test(#3): RED — set" backend/src/test/java/a/BarTest.java 'a\n' frontend/src/c.test.tsx 'a\n' \
  scripts/z-selftest.sh 'exit 0\n'
commit "test(#3): more" backend/src/test/java/a/BazTest.java 'a\n'
out="$(bash "$REDSET" "$(git rev-list --max-parents=0 HEAD)" HEAD 2>&1)"
if grep -qx "classes: BarTest" <<<"$out" && grep -qx "vitest: frontend/src/c.test.tsx" <<<"$out" \
   && grep -qx "selftests: scripts/z-selftest.sh" <<<"$out"; then echo "ok   red-set lists RED-touched tests only"
else echo "FAIL red-set lists RED-touched tests only"; sed 's/^/    /' <<<"$out"; FAIL=1; fi


repo; commit "test(#1): RED — foo" $T 'a\n'; commit "feat: foo" $M 'x\n' $T 'A\n'
echo "Contract-change: $T — oracle was wrong — reviewed-by alice" >"$TMP/body"
expect "lr-2 Contract-change by a human waives" 0 "lr-2 ok" --body-file "$TMP/body"
echo "Contract-change: $T — oracle was wrong — reviewed-by claude-bot" >"$TMP/body"
expect "lr-2 Contract-change by an agent refused" 1 "✗ lr-2" --body-file "$TMP/body"
