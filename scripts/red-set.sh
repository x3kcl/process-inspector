#!/usr/bin/env bash
# red-set.sh (#645): every test a `test(#N): RED` commit in BASE..HEAD touched, listed or run
# together, so children's REDs are proven side by side before a CI minute is spent.
#
#   scripts/red-set.sh [BASE] [HEAD] [--run]       (defaults origin/main HEAD)
#
# Lists  classes: A,B   vitest: frontend/src/x.test.tsx …   selftests: scripts/y-selftest.sh …
# --run: backend `mvn -o -q test -Dtest=…`, frontend `npx vitest run …`, then each selftest;
# ends with  RED-SET: GREEN|RED (…)  (exit 0/1). RED_SET_MVN / RED_SET_VITEST override the runners.
set -uo pipefail
# shellcheck source=scripts/lib/front-door.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/front-door.sh"
POS=() RUN=0
for a in "$@"; do [ "$a" = --run ] && RUN=1 || POS+=("$a"); done
BASE="${POS[0]:-origin/main}" HEAD_REF="${POS[1]:-HEAD}"
cd "$(git rev-parse --show-toplevel)" || exit 2

CLASSES=() VITEST=() SELFTESTS=()
while read -r sha; do
  [ -n "$sha" ] || continue
  while IFS= read -r f; do
    git cat-file -e "$HEAD_REF:$f" 2>/dev/null || continue   # gone again by HEAD
    case "$f" in
      backend/src/test/java/*Test.java | backend/src/test/java/*Tests.java | backend/src/test/java/*IT.java)
        CLASSES+=("$(basename "$f" .java)") ;;
      frontend/src/*.test.ts | frontend/src/*.test.tsx) VITEST+=("$f") ;;
      scripts/*selftest.sh) SELFTESTS+=("$f") ;;
    esac
  done < <(fd_files "$sha")
done < <(fd_frozen_reds "$BASE" "$HEAD_REF")
uniq_() { printf '%s\n' "$@" | sed '/^$/d' | sort -u; }
mapfile -t CLASSES < <(uniq_ "${CLASSES[@]}")
mapfile -t VITEST < <(uniq_ "${VITEST[@]}")
mapfile -t SELFTESTS < <(uniq_ "${SELFTESTS[@]}")
CLASS_LIST="$(IFS=,; echo "${CLASSES[*]}")"
echo "classes: $CLASS_LIST"
echo "vitest: ${VITEST[*]}"
echo "selftests: ${SELFTESTS[*]}"
[ "$RUN" = 1 ] || exit 0

FAILED=()
if [ -n "$CLASS_LIST" ]; then
  # shellcheck disable=SC2086  # RED_SET_MVN may carry arguments
  (cd backend && ${RED_SET_MVN:-mvn} -o -q test "-Dtest=$CLASS_LIST" -Dsurefire.failIfNoSpecifiedTests=false) || FAILED+=(backend)
fi
if [ "${#VITEST[@]}" -gt 0 ]; then
  # shellcheck disable=SC2086
  (cd frontend && ${RED_SET_VITEST:-npx vitest run} "${VITEST[@]#frontend/}") || FAILED+=(vitest)
fi
for s in "${SELFTESTS[@]}"; do bash "$s" || FAILED+=("$s"); done
[ "${#FAILED[@]}" = 0 ] && { echo "RED-SET: GREEN"; exit 0; }
echo "RED-SET: RED (${FAILED[*]})"
exit 1
