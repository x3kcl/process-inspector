#!/usr/bin/env bash
#
# shell-syntax-check.sh — CI gate closing a real, confirmed gap: this repo's growing set of
# deploy/CI shell scripts (deploy/, docker/, docker/backup/*/, scripts/ itself) had NO
# automated syntax check anywhere — every prior verification (issue #201 and its follow-up)
# was a manual `bash -n` pass during that PR's own rehearsal, never re-run by CI afterward.
# Found during #201-followup's adversarial review.
#
# Deliberately `bash -n` (syntax-only), not shellcheck: zero new tooling/runner dependency,
# always available wherever bash itself is. This catches "would fail to even parse" — real
# logic bugs still need a human rehearsal against real infrastructure (see the `deploy/`
# scripts' own header comments for how those are verified) — but a syntax error shipped to
# a script that runs unattended (a cron-scheduled backup, say) is exactly the kind of
# silent, easy-to-miss failure this exists to catch before it reaches that point.
#
# Scope: TRACKED FILES ONLY (git ls-files) with a `#!.../bash` or `#!.../sh` shebang —
# doesn't rely on the .sh extension alone, since a couple of scripts in this repo don't have
# an extension.
#
# Usage: scripts/shell-syntax-check.sh   (exit 0 = clean, exit 1 = findings; run from repo root)

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

fail=0
count=0
while IFS= read -r -d '' file; do
  # Only files git actually tracks as executable-shebang shell scripts — a plain grep on
  # extension would miss extension-less scripts and false-positive on unrelated .sh-suffixed
  # fixtures (none exist today, but the shebang check is the more honest signal either way).
  head1="$(head -c 64 "$file" 2>/dev/null || true)"
  case "$head1" in
    '#!'*bash*|'#!'*'/sh'|'#!'*' sh') ;;
    *) continue ;;
  esac
  count=$((count + 1))
  if ! bash -n "$file" 2>/tmp/shell-syntax-check.err; then
    echo "✗ syntax error: $file"
    sed 's/^/    /' /tmp/shell-syntax-check.err
    fail=1
  fi
done < <(git ls-files -z -- '*.sh' 'deploy' 'docker' 'scripts' '.github' 2>/dev/null)
rm -f /tmp/shell-syntax-check.err

if [[ "$fail" == 1 ]]; then
  echo ""
  echo "shell-syntax-check: FAILED — fix the syntax error(s) above."
  exit 1
fi
echo "shell-syntax-check: OK ($count script(s) checked)"
