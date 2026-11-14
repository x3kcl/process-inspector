# shellcheck shell=bash
# Shared by scripts/front-door-check.sh and scripts/red-set.sh (#645). Sourced, not run.
# Path semantics are ci-lib mq/merge_queue.py (v0.17, RED_FIRST): `dir/` is a tree prefix,
# anything else an fnmatch glob on the path or its basename; a test path wins over a code path.

FD_TEST_DEFAULT="backend/src/test/ frontend/e2e/ frontend/src/*.test.ts frontend/src/*.test.tsx scripts/*selftest.sh"
FD_CODE_DEFAULT="backend/src/main/ frontend/src/ scripts/ ci/"

# fd_manifest REF KEY DEFAULT -> the KEY value of ci/manifest.env at REF, DEFAULT when unset
fd_manifest() {
  local v
  v="$(git show "$1:ci/manifest.env" 2>/dev/null | sed -n "s/^$2=\"\{0,1\}\([^\"]*\)\"\{0,1\}\$/\1/p" | tail -1)"
  printf '%s\n' "${v:-$3}"
}

# fd_hits PATH PATTERN... -> rc 0 when PATH matches a pattern
fd_hits() {
  local p="$1" pat; shift
  for pat in "$@"; do
    case "$pat" in
      */) [[ "$p" == "$pat"* ]] && return 0 ;;
      # shellcheck disable=SC2053  # the pattern is a glob on purpose
      *) [[ "$p" == $pat || "${p##*/}" == $pat ]] && return 0 ;;
    esac
  done
  return 1
}

# fd_files SHA -> files the commit touched (merges: none)
fd_files() { git diff-tree --no-commit-id --name-only -r "$1"; }

# fd_frozen_reds BASE HEAD -> shas of `test(#N): RED` commits in BASE..HEAD, oldest first
# (subject only, as ci-lib RED_FROZEN_RE.match: a body line quoting one does not freeze)
fd_frozen_reds() {
  git log --reverse --format='%H %s' "$1..$2" | while read -r sha subj; do
    [[ "$subj" =~ ^test\(#[0-9]+\):\ RED([^[:alnum:]_]|$) ]] && printf '%s\n' "$sha"
  done
  return 0
}
