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
  bash scripts/contract-selftest.sh
  ./scripts/security-audit.sh
  ./scripts/shell-syntax-check.sh
  ./scripts/pr-queue-selftest.sh
  ./scripts/check-node-pin.sh
  bash scripts/check-alert-rules.sh
  bash scripts/hooks-selftest.sh
  bash scripts/check-orphan-its.sh
  bash scripts/front-door-selftest.sh
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
  (cd "$ROOT/backend" && mvn -B -Dskip.surefire.tests=true package)   # not -DskipTests: see Dockerfile
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
  "migration-findings|flowable-6,flowable-7,postgres|BulkMigrationIT"
  "migration-findings|flowable-6,flowable-7,postgres|HierarchyMigrationIT"
  "migration-findings|flowable-6,flowable-7,postgres|CaseMigrationIT"
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

# ---- ci-nightly -------------------------------------------------------------------------------
# One part per nightly.yml job, named by its job id (CI_PART=<job id>). The workflow keeps the
# triggers, Java/Node setup, logins, timeouts and artifact uploads; the commands live here.
# supply-chain is not a part: its logic is marketplace actions (.github/actions/supply-chain).
# A part that needs docker skips loudly when run by hand without a daemon; in the workflow
# (CI_EVENT set) an unreachable daemon is red.
NIGHTLY_COMPOSE=docker/docker-compose.dev.yml
nightly_docker() { # part — 0 run it, 1 skip it
  docker info > /dev/null 2>&1 && return 0
  [ -z "${CI_EVENT:-}" ] || { echo "contract: $1 needs docker and the daemon is unreachable" >&2; exit 1; }
  echo "contract: SKIPPED $1 — no reachable docker daemon (the nightly runner slot has one)"; return 1
}
nightly_ryuk() { # rootless slots: point Testcontainers' Ryuk at the real socket (no-op elsewhere)
  local tc_env; tc_env="$(bash scripts/ci-testcontainers-socket.sh)"
  [ -z "$tc_env" ] || export "$tc_env"
}
nightly_its() { (cd backend && mvn -B verify -Dskip.surefire.tests=true "$@"); }

nightly_container_its() {
  part container-its
  nightly_docker container-its || return 0
  nightly_ryuk
  nightly_its -Dit.test=AuditRoleGrantsIT,AuditPartitionCarveIT,AuditPartitionMaintainerIT,AuditRetentionPurgeIT,AuditRetentionPurgerIT,AuditIntegrityIT,SharedViewIT,SharedViewFailClosedIT,SharedViewGovernanceIT,ViewStoreIT,AccessMappingAdminDbIT,MappingStoreDbIT,EngineRegistryStoreIT,EngineRegistryReloadIT,OidcKeycloakIT,IncidentLedgerIT,LedgerNativeQueriesIT,AuditEntryRepositoryScopeIT,AuditEntryRepositoryAttributionIT
}
nightly_dlq_scan_cap_p2() { part dlq-scan-cap-p2; nightly_its -Dit.test=DlqScanCapVolumeIT; }
nightly_fanout_partial_failure_p1() { part fanout-partial-failure-p1; nightly_its -Dit.test=FanoutPartialFailureIT; }
nightly_soak_p3() {
  part soak-p3
  nightly_docker soak-p3 || return 0
  nightly_ryuk
  nightly_its -Dit.test=SseBulkSoakIT -Dsoak.minutes=20
}

# The compose harness (flowable-6 + postgres) of engine-its, perf-p1 and e2e-real, on the slot's
# ports (#163/#288). The EXIT trap reaps the background BFF, dumps the logs on a red part and
# always tears the harness down; each part runs in its own subshell.
NIGHTLY_BFF_PID="" NIGHTLY_BFF_LOG=""
nightly_cleanup() {
  local rc=$?
  if [ -n "$NIGHTLY_BFF_PID" ]; then
    [ "$rc" = 0 ] || tail -200 "$NIGHTLY_BFF_LOG" || true
    kill "$NIGHTLY_BFF_PID" 2>/dev/null || true
  fi
  [ "$rc" = 0 ] || docker compose -p "$CI_PROJECT" -f "$NIGHTLY_COMPOSE" logs --no-color --timestamps || true
  docker compose -p "$CI_PROJECT" -f "$NIGHTLY_COMPOSE" down -v --remove-orphans || true
}
nightly_harness() { # project a-port b-port pg-port
  export COMPOSE_PROFILES="flowable-6,postgres" CI_PROJECT="$1"
  PI_ENGINE_A_PORT=$(scripts/free-port.sh "$2" 20)
  PI_ENGINE_B_PORT=$(scripts/free-port.sh "$3" 20 "$PI_ENGINE_A_PORT")
  PI_PG_PORT=$(scripts/free-port.sh "$4" 5)
  export PI_ENGINE_A_PORT PI_ENGINE_B_PORT PI_PG_PORT INSPECTOR_DB_URL="jdbc:postgresql://localhost:${PI_PG_PORT}/inspector"
  docker compose -p "$CI_PROJECT" -f "$NIGHTLY_COMPOSE" down -v --remove-orphans || true    # a killed run's residue
  trap nightly_cleanup EXIT
  docker compose -p "$CI_PROJECT" -f "$NIGHTLY_COMPOSE" up -d
  bash docker/smoke-test.sh flowable-6
}
nightly_bff() { # log — the BFF jar in the background, pointed at the seeded engines, until /v3/api-docs answers
  (cd backend && mvn -B -Dskip.surefire.tests=true package)   # not -DskipTests: see Dockerfile
  NIGHTLY_BFF_LOG="$1"
  cd backend
  INSPECTOR_ENGINE_A_BASE_URL="http://localhost:${PI_ENGINE_A_PORT}/flowable-rest/service" \
  INSPECTOR_ENGINE_B_BASE_URL="http://localhost:${PI_ENGINE_B_PORT}/flowable-rest/service" \
  ENGINE_A_PASSWORD=test ENGINE_B_PASSWORD=test SERVER_PORT="$PI_BFF_PORT" \
    nohup java -jar "$(ls target/*.jar | head -1)" > "$NIGHTLY_BFF_LOG" 2>&1 &
  NIGHTLY_BFF_PID=$!
  cd "$ROOT"
  for _ in $(seq 1 90); do
    curl -fsS "http://localhost:${PI_BFF_PORT}/v3/api-docs" > /dev/null && return 0
    sleep 2
  done
  echo "contract: BFF did not serve /v3/api-docs within 180s" >&2
  return 1
}

nightly_engine_its() {
  part engine-its
  nightly_docker engine-its || return 0
  nightly_harness pi-ci-nightly-6 "${PI_ENGINE_A_PORT:-8691}" "${PI_ENGINE_B_PORT:-8692}" "${PI_PG_PORT:-5435}"
  bash docker/seed.sh --profile flowable-6
  nightly_ryuk
  # SelfHealLikelyLaneIT is several minutes of real engine retries; the job's 40 minutes cover it.
  nightly_its -Dit.test=CorrectiveActionIT,CmmnCorrectiveActionIT,FailClosedAuditIT,FlowSurgeryIT,BulkFilterIT,BulkErrorClassIT,SnapshotSamplerIT,DefinitionVersionsIT,MigrationIT,DetailResolveIT,InstanceTimelineIT,IncidentLedgerArcIT,SelfHealDwellSuppressionIT,SelfHealLikelyLaneIT,SelfHealMixedLaneIT
}

nightly_perf_p1() {
  part perf-p1
  nightly_docker perf-p1 || return 0
  nightly_harness pi-ci-nightly-perf "${PI_ENGINE_A_PORT:-8695}" "${PI_ENGINE_B_PORT:-8696}" "${PI_PG_PORT:-5436}"
  PI_BFF_PORT=$(scripts/free-port.sh "${PI_BFF_PORT:-8697}" 20); export PI_BFF_PORT
  # Full FIX-REF-01 scale; explicit engine URLs (the flowable-6 profile only brings up A+B).
  python3 docker/seed-reference.py \
    "http://localhost:${PI_ENGINE_A_PORT}/flowable-rest/service" "http://localhost:${PI_ENGINE_B_PORT}/flowable-rest/service"
  nightly_bff /tmp/bff-perf.log
  # k6 as a pinned, checksum-verified BINARY on the runner: a containerised k6 has no route back to
  # the runner on the rootless-DinD slots (nightly #91). v2.1.0 keeps perf-scenario-p1.js's thresholds calibrated.
  local ver=2.1.0 sha=295d961ebfca306f295f1133068dcd403a8171c87f387928f5f30b0fbcff858a
  curl -fsSL "https://github.com/grafana/k6/releases/download/v${ver}/k6-v${ver}-linux-amd64.tar.gz" -o /tmp/k6.tgz
  echo "${sha}  /tmp/k6.tgz" | sha256sum -c -
  tar xzf /tmp/k6.tgz -C /tmp
  install -m 0755 "/tmp/k6-v${ver}-linux-amd64/k6" /tmp/k6
  /tmp/k6 version
  # The dev viewer/dev credentials are the k6 script's own defaults (SecurityConfig, !oidc).
  BASE_URL="http://localhost:${PI_BFF_PORT}" /tmp/k6 run --summary-export=/tmp/k6-summary-p1.json scripts/perf-scenario-p1.js
}

nightly_e2e_real() {
  part e2e-real
  nightly_docker e2e-real || return 0
  nightly_harness pi-ci-nightly-e2e "${PI_ENGINE_A_PORT:-8691}" "${PI_ENGINE_B_PORT:-8692}" "${PI_PG_PORT:-5435}"
  PI_BFF_PORT=$(scripts/free-port.sh "${PI_BFF_PORT:-8086}" 20 "$PI_ENGINE_A_PORT" "$PI_ENGINE_B_PORT")
  PI_E2E_PORT=$(scripts/free-port.sh "${PI_E2E_PORT:-4173}" 20)
  export PI_BFF_PORT PI_E2E_PORT
  bash docker/seed.sh --profile flowable-6
  nightly_bff "${RUNNER_TEMP:-/tmp}/bff-e2e.log"   # the workflow uploads it on a red run
  cd frontend
  npm ci
  INSPECTOR_BFF_URL="http://localhost:${PI_BFF_PORT}" npm run e2e:real
}

nightly_supply_chain_triage_drill() { # synthetic red/green for #447: upserts the advisory issue only
  part supply-chain-triage-drill
  if [ -z "${CI_TRIAGE_DRILL:-}" ]; then
    echo "contract: SKIPPED supply-chain-triage-drill — CI_TRIAGE_DRILL (red|green) is unset (the workflow's dispatch input sets it)"
    return 0
  fi
  node scripts/supply-chain-triage.mjs --drill "$CI_TRIAGE_DRILL" --file-issue
}

# ---- dispatch ---------------------------------------------------------------------------------
case "$TARGET" in
  ci-light) default_parts="lint unit frontend e2e" ;;
  ci-heavy) default_parts="docker_build integration" ;;
  ci-nightly) default_parts="container-its dlq-scan-cap-p2 fanout-partial-failure-p1 soak-p3 engine-its perf-p1 e2e-real" ;;
  *) echo "usage: ci/contract.sh ci-light|ci-heavy|ci-nightly   (CI_PART=<part> runs one part)" >&2; exit 2 ;;
esac
for p in ${CI_PART:-$default_parts}; do
  case "$TARGET:$p" in
    ci-light:lint|ci-light:unit|ci-light:frontend|ci-light:e2e|ci-heavy:integration) ( "$p" ) ;;
    ci-heavy:docker|ci-heavy:docker_build) ( docker_build ) ;;
    ci-nightly:container-its|ci-nightly:dlq-scan-cap-p2|ci-nightly:fanout-partial-failure-p1|ci-nightly:soak-p3|\
    ci-nightly:engine-its|ci-nightly:perf-p1|ci-nightly:e2e-real|ci-nightly:supply-chain-triage-drill) ( "nightly_${p//-/_}" ) ;;
    *) echo "contract: '$p' is not a part of $TARGET" >&2; exit 2 ;;
  esac
done
echo; echo "contract: $TARGET green"
