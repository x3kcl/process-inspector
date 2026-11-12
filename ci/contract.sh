#!/usr/bin/env bash
#
# ci/contract.sh <ci-light|ci-heavy> — the ci-lib contract (gate.sh runs it; run it by hand to
# reproduce a gate locally).
#
#   ci-light  lint · unit · frontend · e2e      what a pull request and a mq/** batch both run
#   ci-heavy  docker · integration              what only a mq/** batch runs
#
# CI_PART picks one part (a space-separated list works too): the workflow runs each part as its
# own job so the check names the main ruleset requires (lint, unit, frontend, e2e, docker,
# integration (...)) keep reporting and the parts run in parallel on the runner slots. Unset, a
# target runs all of its parts in order, cheapest first.
#
# Parts that need a service the workflow provides skip loudly when it is absent:
#   frontend's OpenAPI drift gate needs INSPECTOR_DB_URL (the job's Postgres service);
#   integration needs CI_IT_PROFILE, CI_IT_COMPOSE_PROFILES, CI_IT_CLASS (the workflow's matrix leg),
#   and with none of them runs every leg in IT_LEGS below, one after the other.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
ROOT="$PWD"
TARGET="${1:-}"

part() { echo; echo "──────── ci/contract.sh $TARGET: $1 ────────"; }

# ---- ci-light ---------------------------------------------------------------------------------
lint() {
  part lint
  (cd backend && mvn -B spotless:check)
  ./scripts/security-audit.sh
  ./scripts/shell-syntax-check.sh
  ./scripts/check-node-pin.sh
  bash scripts/check-alert-rules.sh
  bash scripts/check-orphan-its.sh
}

unit() {
  part unit
  (cd backend && mvn -B test)
}

frontend() {
  part frontend
  cd frontend
  npm ci
  node scripts/check-toolchain-majors.mjs
  node scripts/check-ag-grid-major.mjs
  node scripts/check-typescript-decision.mjs
  npm run lint
  npm run format:check
  npm run test:coverage
  npm run build
  # OpenAPI drift gate (R-SEM-15): boot the real BFF (engines absent, health degrades) on the
  # job's Postgres, regenerate schema.d.ts, fail on a diff.
  if [ -z "${INSPECTOR_DB_URL:-}" ]; then
    echo "contract: SKIPPED the OpenAPI drift gate — INSPECTOR_DB_URL is unset (the workflow's frontend job sets it)"
    return 0
  fi
  port="" jar="" bffpid=""      # not local: the EXIT trap below runs after this function has returned
  port="$("$ROOT/scripts/free-port.sh" "${PI_BFF_PORT:-8086}")"
  (cd "$ROOT/backend" && mvn -B -DskipTests package)
  jar="$(ls "$ROOT"/backend/target/*.jar | head -1)"
  trap '[ -z "$bffpid" ] || kill "$bffpid" 2>/dev/null || true' EXIT      # each part runs in its own subshell
  ENGINE_A_PASSWORD=unused-in-ci ENGINE_B_PASSWORD=unused-in-ci SERVER_PORT="$port" \
    nohup java -jar "$jar" > /tmp/bff-"$port".log 2>&1 &
  bffpid=$!
  up=0
  for _ in $(seq 1 90); do
    if curl -fsS "http://localhost:$port/v3/api-docs" > /dev/null; then up=1; break; fi
    sleep 2
  done
  if [ "$up" != 1 ]; then echo "contract: BFF did not serve /v3/api-docs within 180s" >&2; tail -200 /tmp/bff-"$port".log; return 1; fi
  INSPECTOR_BFF_URL="http://localhost:$port" npm run gen:api
  git diff --exit-code -- src/api/schema.d.ts || {
    echo "contract: frontend/src/api/schema.d.ts is stale — run the BFF, 'npm run gen:api', and commit it (never hand-edit it)." >&2
    return 1
  }
}

e2e() {
  part e2e
  cd frontend
  PI_E2E_PORT="$("$ROOT/scripts/free-port.sh" "${PI_E2E_PORT:-4173}")"
  export PI_E2E_PORT
  npm ci
  npm run e2e
}

# ---- ci-heavy ---------------------------------------------------------------------------------
docker_build() {
  part docker
  # Build only, no push, no scan (the scan is nightly.yml's: a new CVE must not redden every open PR).
  # The BFF image, then the web image — a PR that breaks frontend/Dockerfile must fail here (#412).
  docker build -t "pi-ci-bff:${CI_GATE_SHA:-local}" .
  docker build -t "pi-ci-web:${CI_GATE_SHA:-local}" frontend
}

# profile|compose profiles|IT classes — keep equal to the integration matrix in .github/workflows/ci.yml
# (its job names are what the ruleset requires). The workflow passes one leg; unset runs all.
IT_LEGS=(
  "flowable-6|flowable-6,postgres|EngineHealthIT,SearchServiceIT,TriageAggregationIT,TriageCmmnScopeIT,CaseDetailIT,KwayPagingIT"
  "flowable-7|flowable-7,postgres|EngineHealth7IT,Search7IT,Triage7IT,ExternalWorkerJob7IT,Kway7IT"
  "flowable-72|flowable-72,postgres|EngineVersionCanaryIT,EngineHealth7IT,Search7IT,Triage7IT,ExternalWorkerJob7IT,Kway7IT"
  "flowable-80|flowable-80,postgres|EngineVersionCanaryIT,EngineHealth7IT,Search7IT,Triage7IT,ExternalWorkerJob7IT,Kway7IT"
  "legacy|legacy,postgres|EngineHealthLegacyIT,SearchLegacyIT,TriageLegacyIT,ExternalWorkerJobLegacyIT,TriageCmmnScopeLegacyIT"
  "flap|flap,postgres|BootLayoutEngineIT"
  "migration-findings|flowable-6,flowable-7,postgres|MigrationFindingsIT"
)

integration_leg() { # profile compose-profiles it-classes — runs in a subshell: its env and ports are its own
  local profile="$1" compose="$2" itclass="$3" slot="${PI_CI_SLOT:-1}"
  part "integration ($profile)"
  if [ "${SKIP_LEG:-}" = 1 ]; then echo "contract: leg $profile skipped (SKIP_LEG=1)"; return 0; fi
  export COMPOSE_PROFILES="$compose"
  # Runner slots share hp04's Docker daemon: every published port is remapped off the dev defaults, and each
  # slot owns a disjoint block (docker/ci-runner/docker-compose.yml). free-port.sh keeps a squatted port
  # from failing the leg (#163/#288).
  local a b seven legacy flap e72 e80 pg flap_pref p72_pref p80_pref it_seven
  a=$(scripts/free-port.sh "${PI_ENGINE_A_PORT:-8691}" 20)
  b=$(scripts/free-port.sh "${PI_ENGINE_B_PORT:-8692}" 20 "$a")
  seven=$(scripts/free-port.sh "${PI_ENGINE_7_PORT:-8693}" 20 "$a" "$b")
  legacy=$(scripts/free-port.sh "${PI_ENGINE_LEGACY_PORT:-8694}" 20 "$a" "$b" "$seven")
  # flap, 7.2 and 8.0 derive from the A-port band when the slot env is absent (+4/+5/+6, #364).
  flap_pref="${PI_ENGINE_FLAP_PORT:-$((a + 4))}"; p72_pref="${PI_ENGINE_72_PORT:-$((a + 5))}"; p80_pref="${PI_ENGINE_80_PORT:-$((a + 6))}"
  [ -n "${PI_ENGINE_FLAP_PORT:-}" ] || echo "WARNING: PI_ENGINE_FLAP_PORT unset — deriving $flap_pref from A-port band; recreate the runner slot" >&2
  [ -n "${PI_ENGINE_72_PORT:-}" ] || echo "WARNING: PI_ENGINE_72_PORT unset — deriving $p72_pref from A-port band; recreate the runner slot" >&2
  [ -n "${PI_ENGINE_80_PORT:-}" ] || echo "WARNING: PI_ENGINE_80_PORT unset — deriving $p80_pref from A-port band; recreate the runner slot" >&2
  flap=$(scripts/free-port.sh "$flap_pref" 20 "$a" "$b" "$seven" "$legacy")
  e72=$(scripts/free-port.sh "$p72_pref" 20 "$a" "$b" "$seven" "$legacy" "$flap")
  e80=$(scripts/free-port.sh "$p80_pref" 20 "$a" "$b" "$seven" "$legacy" "$flap" "$e72")
  pg=$(scripts/free-port.sh "${PI_PG_PORT:-5435}" 5)
  # *7IT and application-it7*.yml read PI_ENGINE_7_PORT: on the 7.2/8.0 legs that is this leg's engine.
  case "$profile" in flowable-72) it_seven=$e72 ;; flowable-80) it_seven=$e80 ;; *) it_seven=$seven ;; esac
  export PI_ENGINE_A_PORT="$a" PI_ENGINE_B_PORT="$b" PI_ENGINE_7_PORT="$it_seven" PI_ENGINE_72_PORT="$e72" \
         PI_ENGINE_80_PORT="$e80" PI_ENGINE_LEGACY_PORT="$legacy" PI_ENGINE_FLAP_PORT="$flap" PI_PG_PORT="$pg"
  export INSPECTOR_DB_URL="jdbc:postgresql://localhost:${pg}/inspector"
  local project="pi-ci-s${slot}-${profile}" compose_file=docker/docker-compose.dev.yml rc=0
  export CI_PROJECT="$project"    # smoke-test.sh scopes its `compose exec` to CI's own project, never a dev stack
  docker compose -p "$project" -f "$compose_file" down -v --remove-orphans || true      # a killed run's residue
  # always tear this leg's own compose project down (expanded now: the trap fires after the function's locals are gone)
  trap "docker compose -p '$project' -f '$compose_file' down -v --remove-orphans || true" EXIT
  {
    docker compose -p "$project" -f "$compose_file" up -d \
      && bash docker/smoke-test.sh "$profile" \
      && bash docker/seed.sh --profile "$profile" \
      && { tc_env="$(bash scripts/ci-testcontainers-socket.sh)"; [ -z "$tc_env" ] || export "$tc_env"; } \
      && (cd backend && mvn -B verify -Dit.test="$itclass" -Dskip.surefire.tests=true)
  } || rc=$?
  if [ "$rc" -ne 0 ]; then docker compose -p "$project" -f "$compose_file" logs --no-color --timestamps || true; fi
  return "$rc"
}

integration() {
  if [ -n "${CI_IT_PROFILE:-}" ]; then
    [ -n "${CI_IT_COMPOSE_PROFILES:-}" ] && [ -n "${CI_IT_CLASS:-}" ] \
      || { echo "contract: CI_IT_PROFILE needs CI_IT_COMPOSE_PROFILES and CI_IT_CLASS" >&2; return 2; }
    ( integration_leg "$CI_IT_PROFILE" "$CI_IT_COMPOSE_PROFILES" "$CI_IT_CLASS" )
    return 0
  fi
  local leg
  for leg in "${IT_LEGS[@]}"; do
    IFS='|' read -r p c i <<< "$leg"
    ( integration_leg "$p" "$c" "$i" )
  done
}

# ---- dispatch ---------------------------------------------------------------------------------
case "$TARGET" in
  ci-light) default_parts="lint unit frontend e2e" ;;
  ci-heavy) default_parts="docker_build integration" ;;
  *) echo "usage: ci/contract.sh ci-light|ci-heavy   (CI_PART=<part> runs one part)" >&2; exit 2 ;;
esac
for p in ${CI_PART:-$default_parts}; do
  case "$TARGET:$p" in
    ci-light:lint|ci-light:unit|ci-light:frontend|ci-light:e2e|ci-heavy:integration) ( "$p" ) ;;
    ci-heavy:docker|ci-heavy:docker_build) ( docker_build ) ;;
    *) echo "contract: '$p' is not a part of $TARGET" >&2; exit 2 ;;
  esac
done
echo; echo "contract: $TARGET green"
