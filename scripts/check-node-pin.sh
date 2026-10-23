#!/usr/bin/env bash
# Gate every shipping Node pin at 24 LTS: Docker node:*-alpine images and
# setup-node node-version. Exit 1 while any of those pins is still 22, or a
# required pin file lacks 24.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

fail=0
bad() { echo "check-node-pin: $*"; fail=1; }

for f in frontend/Dockerfile Dockerfile; do
  grep -qF 'node:24-alpine' "$f" || bad "$f missing node:24-alpine"
done
for f in .github/workflows/ci.yml .github/workflows/nightly.yml .github/actions/supply-chain/action.yml; do
  grep -qF 'node-version: "24"' "$f" || bad "$f missing node-version \"24\""
done

hits="$(git grep -n -e 'node:22-alpine' -e 'node-version: "22"' -- ':!scripts/check-node-pin.sh' || true)"
if [ -n "$hits" ]; then
  printf '%s\n' "$hits"
  bad "pin still on 22"
fi

exit "$fail"
