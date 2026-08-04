#!/usr/bin/env bash
# Zero-flake readiness gate for the dev harness: block (bounded) until every engine of
# the given profile answers REST and postgres accepts TCP connections. CI runs this
# between `docker compose up -d` and `mvn verify` so failsafe never races a booting
# engine. Locally:  COMPOSE_PROFILES=<profile>,postgres bash docker/smoke-test.sh <profile>
# The `migration-findings` alias probes A+B+7 (COMPOSE_PROFILES=flowable-6,flowable-7,postgres).
#
# The engine probe is the SAME call EngineSeed.requireReachable uses — smoke-pass
# implies the ITs' @BeforeAll reachability precheck passes.
set -euo pipefail

PROFILE="${1:?usage: smoke-test.sh <flowable-6|flowable-7|legacy|flap|migration-findings>}"
DEADLINE=$(($(date +%s) + 180)) # engines take 30-60s to boot; 6.3.1 can be slower
# Defaults for the flowable-rest war images; the `flap` profile overrides both below —
# an application that embeds the engine publishes a different context and owns its own
# credentials (see docker-compose.dev.yml's engine-flap service).
CRED="rest-admin:test"
ENGINE_PATH="/flowable-rest/service"
# CI_PROJECT (set by the self-hosted-runner CI job) scopes `compose exec` to CI's own
# project so the postgres readiness probe hits CI's container, not an already-up dev stack.
# Unset locally → the compose file's own `name:` (process-inspector) is used, as before.
COMPOSE="docker compose ${CI_PROJECT:+-p $CI_PROJECT} -f $(dirname "$0")/docker-compose.dev.yml"

# Host ports are env-overridable (default = the doctrinal dev ports) so a self-hosted
# runner on a shared Docker daemon can remap them off an already-up dev/demo stack.
# These MUST agree with docker-compose.dev.yml's PI_ENGINE_*_PORT defaults.
case "$PROFILE" in
  flowable-6) PORTS="${PI_ENGINE_A_PORT:-8081} ${PI_ENGINE_B_PORT:-8082}" ;;
  flowable-7) PORTS="${PI_ENGINE_7_PORT:-8083}" ;;
  legacy)     PORTS="${PI_ENGINE_LEGACY_PORT:-8084}" ;;
  # Dual-major MigrationFindingsIT leg (#362): COMPOSE_PROFILES=flowable-6,flowable-7,postgres
  # so A+B+7 are up. Probe all three — smoke-pass must cover every engine the suite's
  # @BeforeAll will requireReachable on (A + 7); B is compose baggage of the flowable-6
  # profile and is seeded but unused by the IT.
  migration-findings) PORTS="${PI_ENGINE_A_PORT:-8081} ${PI_ENGINE_B_PORT:-8082} ${PI_ENGINE_7_PORT:-8083}" ;;
  flap)
    PORTS="${PI_ENGINE_FLAP_PORT:-8086}"
    ENGINE_PATH="/process-api"
    CRED="inspector:harness"
    ;;
  *) echo "unknown profile: $PROFILE" >&2; exit 2 ;;
esac

wait_for() { # description command...
  local what="$1"; shift
  until "$@" >/dev/null 2>&1; do
    if [ "$(date +%s)" -ge "$DEADLINE" ]; then
      echo "TIMEOUT waiting for $what" >&2
      exit 1
    fi
    sleep 2
  done
  echo "READY: $what"
}

# `curl -sf` is NOT enough: it exits 0 on a 3xx. An application that embeds the engine answers
# an unauthenticated/unmapped path with a 302 to its own login page, so a bare -sf probe reports
# READY for an engine whose REST API is absent or closed — and the failure then surfaces two
# steps later as an unrelated-looking seed error. Demand the 200 explicitly, and demand that the
# body is really the engine document.
engine_answers() { # url
  local body
  body="$(curl -sfu "$CRED" -w '\n%{http_code}' "$1" 2>/dev/null)" || return 1
  [ "${body##*$'\n'}" = "200" ] || return 1
  case "${body%$'\n'*}" in *'"version"'*) return 0 ;; *) return 1 ;; esac
}

for port in $PORTS; do
  url="http://localhost:$port$ENGINE_PATH/management/engine"
  wait_for "engine :$port" engine_answers "$url"
done

# TCP-forced (-h 127.0.0.1): the postgres image accepts SOCKET connections during
# first-boot initdb before restarting — polling the socket false-positives.
# Requires COMPOSE_PROFILES to include `postgres` so exec resolves the service.
wait_for "postgres" $COMPOSE exec -T postgres pg_isready -h 127.0.0.1 -U inspector -d inspector

echo "Harness ready for profile '$PROFILE'."
