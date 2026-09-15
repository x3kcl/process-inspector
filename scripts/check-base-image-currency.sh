#!/usr/bin/env bash
#
# check-base-image-currency.sh — is each shipping image's pinned base still CURRENT?
#
# Closes a blind spot found in #409/#411: frontend/Dockerfile pinned `nginx:1.27-alpine`,
# which tracks alpine 3.21, while the BFF was already on 3.24. That base carried 35 fixable
# HIGH/CRITICAL findings as pulled. Nothing told us — it surfaced only because someone went
# looking during an unrelated fix.
#
# WHY THE TRIVY SCAN DOES NOT ALREADY COVER THIS. The nightly scans the image AFTER
# `apk upgrade`, so a stale base that `apk upgrade` happens to patch scans GREEN: the drift
# is invisible precisely because the compensating mechanism is working. It only becomes
# visible once a CVE lands that `apk upgrade` cannot reach on the old branch — i.e. after it
# is already a problem. A green scan is evidence of a patched image, never of a current base.
# This script measures the thing the scan structurally cannot.
#
# Two questions, both cheap:
#   1. DRIFT   — how far is each pinned base behind current Alpine stable?
#   2. SKEW    — do our own runtime images agree with each other? The moment they diverge,
#                one of them is aging, and that is the earliest honest signal we get.
#
#   bash scripts/check-base-image-currency.sh              # report + exit code
#   MAX_DRIFT=2 bash scripts/check-base-image-currency.sh  # tolerate more minors behind
#
# Exit codes:
#   0  every base within MAX_DRIFT minors of stable, and no skew between our images
#   1  at least one base is too far behind, or our runtime images disagree
#   2  could not determine something (no docker, registry unreachable) — NOT a drift verdict
#
# Read-only: pulls public base images and reads /etc/alpine-release. Touches nothing else.
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 2

# How many Alpine minors behind stable is tolerable. 1 is deliberate slack: upstream image
# tags legitimately lag an Alpine release by a few weeks, and failing on that would train
# people to ignore this. 3.21-vs-3.24 (drift 3) is what this exists to catch.
MAX_DRIFT="${MAX_DRIFT:-1}"

command -v docker >/dev/null 2>&1 || { echo "check-base-image-currency: docker unavailable"; exit 2; }

# The bases we actually ship FROM. Discovered, not hardcoded: a `FROM … AS runtime` line is
# by construction the stage that ends up in the published image (that naming is load-bearing
# elsewhere too — the no-cache-filters pairing in #404/#409 keys off it), so a new shipping
# image is covered the day it is added rather than the day someone remembers this file.
mapfile -t ENTRIES < <(
  git ls-files '*Dockerfile' 'Dockerfile' \
    | while IFS= read -r f; do
        base="$(grep -ioP '^\s*FROM\s+\K\S+(?=\s+AS\s+runtime\s*$)' "$f" | tail -1)"
        [ -n "$base" ] && printf '%s\t%s\n' "$f" "$base"
      done
)
[ "${#ENTRIES[@]}" -gt 0 ] || { echo "check-base-image-currency: no 'FROM ... AS runtime' stages found"; exit 2; }

alpine_release_of() { # alpine_release_of <image-ref> -> "3.24.1" | ""
  docker run --rm --entrypoint sh "$1" -c 'cat /etc/alpine-release 2>/dev/null' 2>/dev/null | tr -d '\r\n'
}
minor_of() { # 3.24.1 -> 24
  printf '%s' "${1:-}" | awk -F. '{print $2+0}'
}

echo "── base-image currency (MAX_DRIFT=${MAX_DRIFT} minor(s) behind stable)"

STABLE="$(alpine_release_of alpine:latest)"
if [ -z "$STABLE" ]; then
  echo "   could not read current Alpine stable (alpine:latest) — registry unreachable?"
  exit 2
fi
STABLE_MINOR="$(minor_of "$STABLE")"
echo "   current Alpine stable: ${STABLE}"
echo

fail=0; unknown=0
declare -A SEEN_MINOR=()
for entry in "${ENTRIES[@]}"; do
  file="${entry%%$'\t'*}"; base="${entry#*$'\t'}"
  rel="$(alpine_release_of "$base")"
  if [ -z "$rel" ]; then
    # Not an alpine base (or unreadable). Not a drift verdict either way — say so plainly
    # rather than scoring it, so a deliberate move to a non-alpine base reads as fine.
    printf '   %-24s %-34s (not alpine / unreadable — skipped)\n' "$file" "$base"
    unknown=1
    continue
  fi
  m="$(minor_of "$rel")"
  drift=$(( STABLE_MINOR - m ))
  SEEN_MINOR["$m"]="${SEEN_MINOR[$m]:-}${file} "
  if [ "$drift" -gt "$MAX_DRIFT" ]; then
    verdict="DRIFT ${drift} minors behind — bump it"; fail=1
  elif [ "$drift" -gt 0 ]; then
    verdict="ok (${drift} behind, within slack)"
  else
    verdict="current"
  fi
  printf '   %-24s %-34s alpine %-8s %s\n' "$file" "$base" "$rel" "$verdict"
done

# Skew between our own images: the earliest signal, and free. If two shipping images sit on
# different Alpine minors, one of them started aging and nobody noticed — which is exactly
# how the web image ended up three minors behind the BFF.
if [ "${#SEEN_MINOR[@]}" -gt 1 ]; then
  echo
  echo "   SKEW: shipping images are on different Alpine minors —"
  for m in "${!SEEN_MINOR[@]}"; do echo "     3.${m}: ${SEEN_MINOR[$m]}"; done
  echo "   Align them; the older one is aging out from under its apk upgrade."
  fail=1
fi

echo
if [ "$fail" -ne 0 ]; then
  echo "── check-base-image-currency: ACTION NEEDED (see above)"
  exit 1
fi
[ "$unknown" -ne 0 ] && echo "── check-base-image-currency: OK (some bases skipped as non-alpine)" \
                     || echo "── check-base-image-currency: OK — every shipping base is current"
exit 0
