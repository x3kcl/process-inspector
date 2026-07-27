#!/usr/bin/env bash
# ci-runner.sh — manage the dockerized self-hosted GitHub Actions runner slots
# (docker/ci-runner/ — port-namespace parallelism, one disjoint port block per slot).
# Host-aware: picks this host's compose file (hp04 → docker-compose.yml, hp02 →
# docker-compose.hp02.yml, mag01 → docker-compose.mag01.yml), so the same commands
# manage whichever host you're on; container/GitHub state it starts, stops and
# counts is this host's slot set only.
#
# Usage: scripts/ci-runner.sh <ensure|status|start|stop|logs>
#
#   ensure  start this host's runner slots if needed and block (bounded) until at least
#           one is ONLINE at GitHub; FAIL if any FOREIGN runner (name not <host>-docker-s<N>
#           for a fleet host, i.e. from no host's slot file) is online — a runner without a slot has no
#           port namespace, so its jobs would race a slot's fixed harness ports (see
#           docker/ci-runner/docker-compose.yml).
#           Run this BEFORE pushing to main or creating a PR (green-ci skill, step 0).
#   status  container states + GitHub-side registered-runner table
#   start   docker compose up -d --build (all slots)
#   stop    docker compose down (all slots)
#   logs    tail a runner container log: logs [slot] [lines]  (default: slot 1, 100)
#
# Needs GITHUB_PERSONAL_ACCESS_TOKEN exported. Env-ref only — never echo or log it.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Each CI host has its own slot file (disjoint port tables surveyed per host — see the
# compose headers). Runner names are prefixed with the host, so "our slots" splits into
# LOCAL (this host's — what start/stop/ensure manage) and the repo-wide slot pattern
# (any host's — what the foreign-runner invariant accepts).
case "$(hostname -s)" in
  hp02)  COMPOSE_FILE="$SCRIPT_DIR/../docker/ci-runner/docker-compose.hp02.yml" ;;
  mag01) COMPOSE_FILE="$SCRIPT_DIR/../docker/ci-runner/docker-compose.mag01.yml" ;;
  *)     COMPOSE_FILE="$SCRIPT_DIR/../docker/ci-runner/docker-compose.yml" ;;
esac
REPO="x3kcl/process-inspector"
SLOT_NAME_PATTERN='^(hp0[0-9]+|mag[0-9]+)-docker-s[0-9]+$'
LOCAL_SLOT_PREFIX="$(hostname -s)-docker-s"

die() { echo "ci-runner: $*" >&2; exit 1; }
need_pat() { [[ -n "${GITHUB_PERSONAL_ACCESS_TOKEN:-}" ]] || die "GITHUB_PERSONAL_ACCESS_TOKEN is not exported"; }

api() {
  curl -fsS -H "Authorization: Bearer $GITHUB_PERSONAL_ACCESS_TOKEN" \
       -H "Accept: application/vnd.github+json" \
       "https://api.github.com/repos/$REPO/$1"
}

running_containers() { docker ps --format '{{.Names}}' | grep -c '^pi-ci-runner-s[0-9]\+$' || true; }
defined_slots() { docker compose -f "$COMPOSE_FILE" config --services | wc -l; }

runners_table() { # "name status busy|idle" per registered runner
  api actions/runners | python3 -c '
import json, sys
for r in json.load(sys.stdin)["runners"]:
    print(r["name"], r["status"], "busy" if r["busy"] else "idle")'
}

online_names() { runners_table | awk '$2 == "online" { print $1 }'; }

cmd_status() {
  echo "runner containers running: $(running_containers)/$(defined_slots)"
  docker ps -f name=pi-ci-runner --format '  {{.Names}} {{.Status}}'
  need_pat
  echo "registered runners (GitHub):"
  runners_table | sed 's/^/  /'
}

cmd_start() {
  need_pat
  docker compose -f "$COMPOSE_FILE" up -d --build
}

cmd_stop() { docker compose -f "$COMPOSE_FILE" down; }

cmd_logs() { docker logs --tail "${2:-100}" "pi-ci-runner-s${1:-1}"; }

cmd_ensure() {
  need_pat
  local want
  want="$(defined_slots)"
  if [[ "$(running_containers)" -lt "$want" ]]; then
    echo "ci-runner: $(running_containers)/$want slot containers running — starting all"
    cmd_start
  fi
  # Bounded wait: ephemeral re-registration after a job takes a few seconds to show online.
  # A transient API failure yields an empty list for that poll and is retried, not fatal.
  # Gate on LOCAL slots only: the other host's fleet being green must not mask this
  # host's slots being dead (the foreign-runner invariant below stays repo-wide).
  local names="" n=0
  for _ in $(seq 1 30); do
    names="$(online_names || true)"
    n="$(printf '%s' "$names" | grep -c "^$LOCAL_SLOT_PREFIX" || true)"
    [[ "$n" -ge 1 ]] && break
    sleep 2
  done
  [[ "$n" -ge 1 ]] || { cmd_status; die "no ${LOCAL_SLOT_PREFIX}<N> runner ONLINE within 60s — check 'scripts/ci-runner.sh logs <slot>'"; }
  # Port-namespace invariant: every online runner must be one of OUR slots. A foreign
  # runner (stray bare-metal install, hand-registered box) has no slot and would race
  # the fixed harness ports of whichever slot runs alongside it.
  local foreign
  foreign="$(printf '%s\n' "$names" | grep -vE "$SLOT_NAME_PATTERN" || true)"
  if [[ -n "$foreign" ]]; then
    cmd_status
    die "foreign runner(s) ONLINE without a port slot: $(echo "$foreign" | tr '\n' ' ')— deregister them (kill the process or DELETE /repos/$REPO/actions/runners/{id})"
  fi
  echo "ci-runner: OK — $n local slot runner(s) online (all online runners):"
  runners_table | awk '$2 == "online" { print "  " $0 }'
  [[ "$n" -lt "$want" ]] && echo "ci-runner: note — $((want - n)) local slot(s) not online (mid-job re-registration is normal; persistent gaps: 'scripts/ci-runner.sh logs <slot>')"
  return 0
}

case "${1:-}" in
  ensure) cmd_ensure ;;
  status) cmd_status ;;
  start)  cmd_start ;;
  stop)   cmd_stop ;;
  logs)   shift; cmd_logs "${1:-1}" "${2:-100}" ;;
  *) grep '^#' "$0" | sed -n '2,21p' | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
