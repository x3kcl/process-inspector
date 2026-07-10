#!/usr/bin/env bash
# ci-runner.sh — manage the dockerized self-hosted GitHub Actions runner (docker/ci-runner/).
#
# Usage: scripts/ci-runner.sh <ensure|status|start|stop|logs>
#
#   ensure  start the runner if needed and block (bounded) until it is ONLINE at GitHub;
#           FAIL if more than one runner is online — the CI design requires exactly one
#           (fixed remapped harness ports; see docker/ci-runner/docker-compose.yml).
#           Run this BEFORE pushing to main or creating a PR (green-ci skill, step 0).
#   status  container state + GitHub-side registered-runner table
#   start   docker compose up -d --build
#   stop    docker compose down
#   logs    tail the runner container log (optional: line count)
#
# Needs GITHUB_PERSONAL_ACCESS_TOKEN exported. Env-ref only — never echo or log it.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/../docker/ci-runner/docker-compose.yml"
REPO="x3kcl/process-inspector"
CONTAINER="pi-ci-runner"

die() { echo "ci-runner: $*" >&2; exit 1; }
need_pat() { [[ -n "${GITHUB_PERSONAL_ACCESS_TOKEN:-}" ]] || die "GITHUB_PERSONAL_ACCESS_TOKEN is not exported"; }

api() {
  curl -fsS -H "Authorization: Bearer $GITHUB_PERSONAL_ACCESS_TOKEN" \
       -H "Accept: application/vnd.github+json" \
       "https://api.github.com/repos/$REPO/$1"
}

container_running() { [[ -n "$(docker ps -q -f "name=^${CONTAINER}\$" -f status=running)" ]]; }

runners_table() { # "name status busy|idle" per registered runner
  api actions/runners | python3 -c '
import json, sys
for r in json.load(sys.stdin)["runners"]:
    print(r["name"], r["status"], "busy" if r["busy"] else "idle")'
}

online_count() { runners_table | awk '$2 == "online"' | wc -l; }

cmd_status() {
  if container_running; then
    echo "container: $CONTAINER running"
  else
    echo "container: $CONTAINER NOT running"
  fi
  need_pat
  echo "registered runners (GitHub):"
  runners_table | sed 's/^/  /'
}

cmd_start() {
  need_pat
  docker compose -f "$COMPOSE_FILE" up -d --build
}

cmd_stop() { docker compose -f "$COMPOSE_FILE" down; }

cmd_logs() { docker logs --tail "${1:-100}" "$CONTAINER"; }

cmd_ensure() {
  need_pat
  if ! container_running; then
    echo "ci-runner: container not running — starting it"
    cmd_start
  fi
  # Bounded wait: ephemeral re-registration after a job takes a few seconds to show online.
  # A transient API failure counts as "not online yet" and is retried, not fatal.
  n=0
  for _ in $(seq 1 30); do
    n="$(online_count || echo 0)"
    [[ "$n" -ge 1 ]] && break
    sleep 2
  done
  [[ "$n" -ge 1 ]] || { cmd_status; die "no runner ONLINE within 60s — check 'scripts/ci-runner.sh logs'"; }
  if [[ "$n" -gt 1 ]]; then
    cmd_status
    die "$n runners ONLINE — the serialized-runner invariant is broken; stop the extra one (a stray bare-metal ~/actions-runner/run.sh?)"
  fi
  echo "ci-runner: OK — exactly one runner online:"
  runners_table | awk '$2 == "online" { print "  " $0 }'
}

case "${1:-}" in
  ensure) cmd_ensure ;;
  status) cmd_status ;;
  start)  cmd_start ;;
  stop)   cmd_stop ;;
  logs)   shift; cmd_logs "${1:-100}" ;;
  *) grep '^#' "$0" | sed -n '2,16p' | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
