#!/usr/bin/env bash
#
# ci-local.sh — run the GitHub CI gates locally BEFORE pushing, so a red main is
# caught in seconds on this box instead of ~15 minutes into the remote matrix.
#
# This is the shift-left half of the `green-ci` skill. It mirrors .github/workflows/ci.yml
# gate-for-gate, cheapest-first, and — like Prettier — keeps going after a failure so it
# reports EVERY broken gate in one pass instead of stopping at the first.
#
#   scripts/ci-local.sh            # fast tier: the gates that catch ~all red-main incidents
#   scripts/ci-local.sh --full     # + backend unit ladder (mvn -B test, minutes)
#   scripts/ci-local.sh --fix      # auto-apply the mechanical fixers first (spotless:apply,
#                                   #   prettier --write), then verify
#
# What it does NOT cover (needs the dockerized engine matrix or a running BFF — see the
# engine-harness skill and the OpenAPI drift gate): the integration matrix, the Docker image
# build, and the schema.d.ts drift gate. Those are verified by ci-watch.sh on the real run.
#
# Exit 0 iff every gate it ran passed.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FULL=0 FIX=0
for a in "$@"; do
  case "$a" in
    --full) FULL=1 ;;
    --fix) FIX=1 ;;
    *) echo "unknown flag: $a" >&2; exit 2 ;;
  esac
done

declare -a NAMES RESULTS
run() { # run <label> <cmd...>
  local label="$1"; shift
  echo ""
  echo "──────── $label ────────"
  if "$@"; then NAMES+=("$label"); RESULTS+=("ok"); else NAMES+=("$label"); RESULTS+=("FAIL"); fi
}

if [[ "$FIX" == 1 ]]; then
  echo "── applying mechanical fixers (spotless:apply, prettier --write) ──"
  ( cd "$ROOT/backend" && mvn -B -q spotless:apply ) || true
  ( cd "$ROOT/frontend" && npx prettier --write . >/dev/null ) || true
fi

# ---- lint job ----
run "backend: spotless:check"      bash -c "cd '$ROOT/backend' && mvn -B -q spotless:check"
run "security-audit (env-refs)"    bash -c "cd '$ROOT' && ./scripts/security-audit.sh"

# ---- frontend job (minus the drift gate, which needs a running BFF) ----
run "frontend: eslint"             bash -c "cd '$ROOT/frontend' && npm run --silent lint"
run "frontend: prettier check"     bash -c "cd '$ROOT/frontend' && npm run --silent format:check"
run "frontend: vitest"             bash -c "cd '$ROOT/frontend' && npm run --silent test"
run "frontend: build (tsc+vite+guards)" bash -c "cd '$ROOT/frontend' && npm run --silent build"

# ---- unit job (opt-in: slower) ----
if [[ "$FULL" == 1 ]]; then
  run "backend: unit ladder (mvn -B test)" bash -c "cd '$ROOT/backend' && mvn -B test"
fi

# ---- summary ----
echo ""
echo "════════════════ ci-local summary ════════════════"
fail=0
for i in "${!NAMES[@]}"; do
  mark="${RESULTS[$i]}"
  [[ "$mark" == "FAIL" ]] && fail=1
  printf '  [%s] %s\n' "$mark" "${NAMES[$i]}"
done
echo ""
if [[ "$fail" == 1 ]]; then
  echo "ci-local: RED — fix the gates above before pushing (try --fix for the mechanical ones)."
  exit 1
fi
if [[ "$FULL" != 1 ]]; then
  echo "ci-local: fast tier GREEN. Heavy gates (backend unit via --full; integration matrix,"
  echo "          Docker image, OpenAPI drift) run on the real CI — watch with scripts/ci-watch.sh."
else
  echo "ci-local: GREEN (fast tier + backend unit). Integration matrix / drift gate still run remotely."
fi
