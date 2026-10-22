#!/usr/bin/env bash
# Fail the lint job when a concrete *IT is named in no workflow and is not explicitly
# local-only. A class is accounted for when its simple name appears in an itClass /
# it.test list under .github/workflows, or the source has @Tag("local-only") and a
# "// reason:" comment saying why CI cannot run it. Abstract *IT bases are skipped.
#
#   scripts/check-orphan-its.sh            # scan this repo
#   scripts/check-orphan-its.sh --selftest # classifier only, no repo scan
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

named_in() {
  # ponytail: token scan of the it-list lines, not a YAML parser. Breaks if a class
  # name is split across lines; upgrade path is a real YAML load of those two keys.
  grep -hE 'itClass:|it\.test=' "$@" \
    | tr -cs 'A-Za-z0-9_' '\n' \
    | grep -E 'IT$' \
    | sort -u
}

# stdout: one "orphan ..." line, or empty when the file is accounted for.
classify() {
  local file="$1" named="$2" class
  class="$(basename "$file" .java)"
  if grep -qE '^[[:space:]]*abstract[[:space:]]+class[[:space:]]' "$file"; then
    return 0
  fi
  if grep -qx "$class" <<<"$named"; then
    return 0
  fi
  if grep -q '@Tag("local-only")' "$file" && grep -q '// reason:' "$file"; then
    return 0
  fi
  echo "orphan IT: $class ($file) — name it in a workflow itClass/it.test list, or @Tag(\"local-only\") with a // reason: comment"
}

scan() {
  local root="$1" named fail=0 line
  named="$(named_in "$root"/.github/workflows/*.yml)"
  while IFS= read -r -d '' file; do
    line="$(classify "$file" "$named" || true)"
    if [[ -n "$line" ]]; then
      echo "$line"
      fail=1
    fi
  done < <(find "$root/backend/src/test" -name '*IT.java' -print0)
  return "$fail"
}

selftest() {
  local tmp named f
  tmp="$(mktemp -d)"
  mkdir -p "$tmp/backend/src/test" "$tmp/.github/workflows"
  printf 'itClass: ListedIT\n' > "$tmp/.github/workflows/ci.yml"
  printf 'abstract class AbstractBaseIT {}\n' > "$tmp/backend/src/test/AbstractBaseIT.java"
  printf 'class ListedIT {}\n' > "$tmp/backend/src/test/ListedIT.java"
  printf 'class FreshOrphanIT {}\n' > "$tmp/backend/src/test/FreshOrphanIT.java"
  printf '@Tag("local-only")\nclass UntaggedReasonIT {}\n' > "$tmp/backend/src/test/UntaggedReasonIT.java"
  printf '@Tag("local-only")\n// reason: needs a hardware dongle CI does not have\nclass LocalOnlyIT {}\n' \
    > "$tmp/backend/src/test/LocalOnlyIT.java"
  named="$(named_in "$tmp"/.github/workflows/*.yml)"
  f=0
  classify "$tmp/backend/src/test/AbstractBaseIT.java" "$named" && \
  classify "$tmp/backend/src/test/ListedIT.java" "$named" && \
  classify "$tmp/backend/src/test/LocalOnlyIT.java" "$named" || f=1
  classify "$tmp/backend/src/test/FreshOrphanIT.java" "$named" | grep -q 'FreshOrphanIT' || f=1
  classify "$tmp/backend/src/test/UntaggedReasonIT.java" "$named" | grep -q 'UntaggedReasonIT' || f=1
  rm -rf "$tmp"
  if [[ "$f" != 0 ]]; then
    echo "check-orphan-its: selftest failed" >&2
    return 1
  fi
  echo "check-orphan-its: selftest ok"
}

if [[ "${1:-}" == "--selftest" ]]; then
  selftest
  exit 0
fi

selftest
scan "$ROOT"
