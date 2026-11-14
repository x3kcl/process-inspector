#!/usr/bin/env bash
# Front-door check (#645): learn before the queue does that BASE..HEAD breaks a landing rule.
#
#   scripts/front-door-check.sh [BASE] [HEAD] [--body-file F]     (defaults origin/main HEAD)
#
#   lr-1  a commit touching a code path needs an earlier commit in the range touching a test path
#         with RED in its subject, or a `No-RED: <reason>` trailer
#   lr-2  a test-path file a `test(#N): RED` commit touched is identical at HEAD to its last RED copy;
#         only a later RED commit may append cases (Contract-change signed by a human waives)
#   lr-3  a test-path hunk that removes strong matchers and adds fewer needs a
#         `Contract-change: <path> — …` line in the pull-request body (--body-file)
#
# Path lists: CI_MQ_RED_TEST_PATHS / CI_MQ_RED_CODE_PATHS of ci/manifest.env at BASE.
# Rules: docs/landing-rules.md. Exit 0 ok, 1 refused, 2 usage. Reads git only.
set -uo pipefail
# shellcheck source=scripts/lib/front-door.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/front-door.sh"

POS=() BODY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --body-file) BODY="$(cat -- "${2:?--body-file needs a path}")" || exit 2; shift 2 ;;
    -*) echo "usage: front-door-check.sh [BASE] [HEAD] [--body-file F]" >&2; exit 2 ;;
    *) POS+=("$1"); shift ;;
  esac
done
BASE="${POS[0]:-origin/main}" HEAD_REF="${POS[1]:-HEAD}"
cd "$(git rev-parse --show-toplevel)" || exit 2
git rev-parse -q --verify "$BASE^{commit}" >/dev/null && git rev-parse -q --verify "$HEAD_REF^{commit}" >/dev/null \
  || { echo "front-door-check: unknown ref $BASE or $HEAD_REF" >&2; exit 2; }
read -ra TESTS <<<"$(fd_manifest "$BASE" CI_MQ_RED_TEST_PATHS "$FD_TEST_DEFAULT")"
read -ra CODE <<<"$(fd_manifest "$BASE" CI_MQ_RED_CODE_PATHS "$FD_CODE_DEFAULT")"
RC=0
refuse() { echo "✗ $1"; RC=1; }
verdict() { [ "$2" = 0 ] && echo "$1 ok"; echo "rule: docs/landing-rules.md#$1"; }

# lr-1 RED first
bad=0 red_seen=0
while read -r sha; do
  [ -n "$sha" ] || continue
  subj="$(git log -1 --format=%s "$sha")" t=0 code=""
  while IFS= read -r f; do
    if fd_hits "$f" "${TESTS[@]}"; then t=1
    elif [ -z "$code" ] && fd_hits "$f" "${CODE[@]}"; then code="$f"; fi
  done < <(fd_files "$sha")
  [ "$t" = 1 ] && [[ "$subj" =~ (^|[^[:alnum:]_])RED([^[:alnum:]_]|$) ]] && red_seen=1
  if [ -n "$code" ] && [ "$red_seen" = 0 ] && ! git log -1 --format=%B "$sha" | grep -qE '^No-RED: [^[:space:]]'; then
    refuse "lr-1 ${sha:0:7} ($subj) changes $code before any RED test commit; commit the failing test first or add a 'No-RED: <reason>' trailer"
    bad=1
  fi
done < <(git log --reverse --format=%H "$BASE..$HEAD_REF")
verdict lr-1 "$bad"

# lr-2 frozen RED (ci-lib red_frozen_paths): HEAD matches the LAST RED copy byte for byte, each
# earlier RED copy survives in the next as an in-order subsequence of lines; a human-signed
# `Contract-change: <path> — <why> — reviewed-by <name>` (PR body or commit body) exempts the path
AGENT_RE="$(fd_manifest "$BASE" CI_MQ_AGENT_REVIEWER_RE "$FD_AGENT_DEFAULT")"
git show "$BASE:ci/manifest.env" 2>/dev/null | grep -qx 'CI_MQ_AGENT_REVIEWER_RE=""\{0,1\}' && AGENT_RE=""
declare -A EXEMPT=() LAST=() BROKE=()
while read -r p who; do
  [ -n "$p" ] && ! { [ -n "$AGENT_RE" ] && grep -qiE "$AGENT_RE" <<<"$who"; } && EXEMPT[$p]=1
done < <({ printf '%s\n' "$BODY"; git log --no-merges --format=%b "$BASE..$HEAD_REF"; } \
  | sed -nE 's/^Contract-change: ([^[:space:]]+) — .+ — reviewed-by ([^[:space:]]+).*/\1 \2/p')
ORDER=()
while read -r sha; do
  [ -n "$sha" ] || continue
  while IFS= read -r f; do
    fd_hits "$f" "${TESTS[@]}" && [ -z "${EXEMPT[$f]-}" ] || continue
    if [ -z "${LAST[$f]-}" ]; then ORDER+=("$f")
    elif ! fd_kept "${LAST[$f]}" "$sha" "$f"; then BROKE[$f]=1; fi
    LAST[$f]="$sha"
  done < <(fd_files "$sha")
done < <(fd_frozen_reds "$BASE" "$HEAD_REF")
bad=0
for f in "${ORDER[@]}"; do
  [ -z "${BROKE[$f]-}" ] && git diff --quiet "${LAST[$f]}" "$HEAD_REF" -- "$f" && continue
  refuse "lr-2 $f differs from its last RED commit ${LAST[$f]:0:7}; restore it (only a later 'test(#N): RED' commit may append cases) or add a human-signed 'Contract-change: $f — <why> — reviewed-by <name>'"
  bad=1
done
verdict lr-2 "$bad"

# lr-3 no weakened assertion, counted per hunk
STRONG='isEqualTo\(|isEqualByComparingTo\(|containsExactly|hasSize\(|isInstanceOf\(|hasMessageContaining\(|hasMessage\(|assertEquals\(|assertThatThrownBy\(|toEqual\(|toStrictEqual\(|toBe\(|toHaveTextContent\(|toHaveLength\(|toHaveBeenCalledWith\(|toThrow\(|toMatchObject\('
bad=0 file="" rm=0 add=0
flush() {
  if [ "$rm" -gt "$add" ] && ! grep -qF "Contract-change: $file " <<<"$BODY"; then
    refuse "lr-3 $file drops strong assertions in one hunk (removed $rm, added $add); keep them or add 'Contract-change: $file — <why> — reviewed-by <human>' to the PR body"
    bad=1
  fi
  rm=0 add=0
}
mapfile -t TFILES < <(git diff --name-only "$BASE" "$HEAD_REF" | while IFS= read -r f; do fd_hits "$f" "${TESTS[@]}" && echo "$f"; done)
if [ "${#TFILES[@]}" -gt 0 ]; then
  while IFS= read -r line; do
    case "$line" in
      "--- a/"*) flush; file="${line#--- a/}" ;;
      "+++ b/"*) flush; file="${line#+++ b/}" ;;
      "@@"*) flush ;;
      "---"* | "+++"*) ;;
      -*) grep -qE "$STRONG" <<<"$line" && rm=$((rm + $(grep -oE "$STRONG" <<<"$line" | wc -l))) ;;
      +*) grep -qE "$STRONG" <<<"$line" && add=$((add + $(grep -oE "$STRONG" <<<"$line" | wc -l))) ;;
    esac
  done < <(git diff -U0 --no-color "$BASE" "$HEAD_REF" -- "${TFILES[@]}")
  flush
fi
verdict lr-3 "$bad"
exit "$RC"
