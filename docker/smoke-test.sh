#!/usr/bin/env bash
# Zero-flake readiness gate for the dev harness: block (bounded) until every engine of
# the given profile answers REST and postgres accepts TCP connections. CI runs this
# between `docker compose up -d` and `mvn verify` so failsafe never races a booting
# engine. Locally:  COMPOSE_PROFILES=<profile>,postgres bash docker/smoke-test.sh <profile>
#
# The engine probe is the SAME call EngineSeed.requireReachable uses — smoke-pass
# implies the ITs' @BeforeAll reachability precheck passes.
set -euo pipefail

PROFILE="${1:?usage: smoke-test.sh <flowable-6|flowable-7|legacy>}"
DEADLINE=$(($(date +%s) + 180)) # engines take 30-60s to boot; 6.3.1 can be slower
CRED="rest-admin:test"
COMPOSE="docker compose -f $(dirname "$0")/docker-compose.dev.yml"

case "$PROFILE" in
  flowable-6) PORTS="8081 8082" ;;
  flowable-7) PORTS="8083" ;;
  legacy)     PORTS="8084" ;;
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

for port in $PORTS; do
  url="http://localhost:$port/flowable-rest/service/management/engine"
  wait_for "engine :$port" curl -sfu "$CRED" -o /dev/null "$url"
done

# TCP-forced (-h 127.0.0.1): the postgres image accepts SOCKET connections during
# first-boot initdb before restarting — polling the socket false-positives.
# Requires COMPOSE_PROFILES to include `postgres` so exec resolves the service.
wait_for "postgres" $COMPOSE exec -T postgres pg_isready -h 127.0.0.1 -U inspector -d inspector

echo "Harness ready for profile '$PROFILE'."
