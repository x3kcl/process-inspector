#!/usr/bin/env bash
#
# ci-k6-base-url.sh — work out which address a CONTAINER started by this job's daemon can
# use to reach the BFF the job booted on the runner itself.
#
# THE BUG THIS EXISTS FOR. nightly's `perf-p1` boots the BFF as a plain `java -jar` on the
# runner, then drives it with k6 in a container:
#
#   docker run --rm --network host -e BASE_URL="http://localhost:$PI_BFF_PORT" grafana/k6 ...
#
# That hardcodes an assumption — "the container's localhost is the runner's localhost" —
# which holds on the host-socket slots (hp04, mag01) and does NOT hold on hp02, whose slots
# were rebuilt on 2026-09-02 onto per-slot ROOTLESS docker-in-docker (the same rebuild that
# broke Testcontainers' Ryuk, fixed in #417 — this is the one job that fix did not cover).
# Under rootless dind the daemon runs its containers in a RootlessKit child network
# namespace; `--network host` means that child namespace, not the runner's. Compose-published
# ports still reach the runner because RootlessKit forwards them outward, which is why the
# engines and Postgres work and only this one hop fails:
#
#   level=warning msg="Request Failed" error="Get \"http://localhost:8186/api/triage\":
#     dial tcp 127.0.0.1:8186: connect: connection refused"
#   GoError: setup: GET /api/triage returned 0
#
# The evidence that it is the slot and not the BFF: the job's own bounded wait on
# /v3/api-docs PASSES seconds earlier (that curl runs on the runner, not in a container), the
# BFF log ends mid-life with no shutdown sequence, and across nightly runs #85-#90 perf-p1
# failed on every hp02 slot it landed on (s1/s3/s4/s6) and passed on every hp04 one.
#
# WHY PROBE RATHER THAN HARDCODE A SECOND ADDRESS. The runner labels do not distinguish the
# hosts (all are `self-hosted,Linux,X64,default`), so the job cannot be pinned to a topology,
# and the right address differs per topology. So ask, with the SAME network flags k6 will
# use — anything else proves nothing about what k6 will see.
#
# PRECONDITION THIS RESTS ON. The BFF must bind all interfaces, not loopback: a process bound
# to 127.0.0.1 is invisible from any other network namespace no matter which address is used,
# and every candidate below would fail. It does — backend/src/main/resources/application.yml
# sets `server.port` only and never `server.address`, so Spring Boot's all-interfaces default
# applies. If anyone ever pins `server.address: 127.0.0.1`, this script starts failing on the
# rootless slots and THIS is the comment that explains why.
#
# Usage (in a workflow step, after the BFF is up and before k6):
#   run: bash scripts/ci-k6-base-url.sh >> "$GITHUB_ENV"
# Prints a single `PI_K6_BASE_URL=<url>` line for $GITHUB_ENV, or exits 1 naming every
# address it tried. Diagnostics go to STDERR on purpose — stdout is consumed as $GITHUB_ENV.
#
# Keep DOCKER_NET_FLAGS in lockstep with the k6 step's own flags in nightly.yml.
set -uo pipefail

PORT="${1:-${PI_BFF_PORT:-}}"
PROBE_IMAGE="${PI_PROBE_IMAGE:-curlimages/curl:8.16.0}"
# `--add-host=host.docker.internal:host-gateway` is what makes candidate 2 resolvable at all;
# the k6 step must pass the same flag or the URL this script picks will not work there.
DOCKER_NET_FLAGS=(--network host --add-host=host.docker.internal:host-gateway)

log() { echo "ci-k6-base-url: $*" >&2; }

if [ -z "$PORT" ]; then
  log "no port given and PI_BFF_PORT is unset"
  exit 1
fi
if ! command -v docker >/dev/null 2>&1; then
  log "docker CLI unavailable — cannot probe"
  exit 1
fi

# /v3/api-docs is the same unauthenticated endpoint the job's own readiness gate uses, so a
# 200 here means exactly what that gate means — reachable AND serving.
probe() { # url -> 0 if a container can fetch it
  docker run --rm "${DOCKER_NET_FLAGS[@]}" "$PROBE_IMAGE" \
    -fsS --max-time 5 "$1/v3/api-docs" >/dev/null 2>&1
}

# Order matters: localhost first keeps this a no-op on the host-socket slots, where it is
# both correct and the cheapest answer.
CANDIDATES=(
  "http://localhost:${PORT}"
  "http://host.docker.internal:${PORT}"
  "http://172.17.0.1:${PORT}"
)

log "probing ${#CANDIDATES[@]} candidate address(es) with: docker run ${DOCKER_NET_FLAGS[*]}"
for url in "${CANDIDATES[@]}"; do
  if probe "$url"; then
    log "reachable from a container: $url"
    echo "PI_K6_BASE_URL=${url}"
    exit 0
  fi
  log "  not reachable: $url"
done

log "FAILED — no candidate address reached the BFF from inside a container."
log "The BFF is up on the runner (the readiness gate passed); this slot's daemon just cannot"
log "route to it. Tried: ${CANDIDATES[*]}"
log "Daemon: $(docker info --format '{{.ServerVersion}} security={{json .SecurityOptions}}' 2>/dev/null || echo unknown)"
exit 1
