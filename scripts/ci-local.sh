#!/usr/bin/env bash
# ci-local.sh — thin wrapper over the ci-lib contract: the same light gates a pull request runs.
#   scripts/ci-local.sh          == bash ci/contract.sh ci-light  (CI_PART=lint|unit|frontend|e2e for one part)
#   scripts/ci-local.sh --fix    apply spotless + prettier first, then verify
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
if [[ "${1:-}" == --fix ]]; then
  (cd backend && mvn -B -q spotless:apply) || true
  (cd frontend && npx prettier --write . >/dev/null) || true
fi
exec bash ci/contract.sh ci-light
