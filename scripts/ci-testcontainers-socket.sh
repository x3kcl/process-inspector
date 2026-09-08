#!/usr/bin/env bash
#
# ci-testcontainers-socket.sh — teach Testcontainers where the Docker socket really is,
# on runner slots whose job daemon is ROOTLESS docker-in-docker.
#
# THE BUG THIS EXISTS FOR. Testcontainers starts its Ryuk reaper with a hardcoded bind
# mount of `/var/run/docker.sock`. On a slot backed by `docker:*-dind-rootless` that path
# does not exist inside the daemon — the socket lives at `$XDG_RUNTIME_DIR/docker.sock`
# (`/run/user/<uid>/docker.sock`). Docker then materialises the missing bind SOURCE as an
# empty directory, Ryuk gets a directory where it expects a socket and dies instantly, and
# because Ryuk is started with autoRemove the container is already gone when Testcontainers
# inspects it. The surfaced error names neither the socket nor the mount:
#
#   ContainerLaunchException: Container startup failed for image testcontainers/ryuk:0.12.0
#   Caused by: NotFoundException: Status 404: {"message":"No such container: b5bf6ab9..."}
#
# (Same failure shape as the demo postgres trap in #396: a missing bind source silently
# becomes an empty directory, and the damage surfaces far from the cause.)
#
# Measured directly against a live slot daemon:
#   -v /var/run/docker.sock:/var/run/docker.sock   -> container GONE after 4s (the 404)
#   -v /run/user/1000/docker.sock:/var/run/...     -> Up, "client processing started"
#
# WHY DETECT RATHER THAN SET IT EVERYWHERE. Exporting this unconditionally would BREAK the
# host-socket slots (hp04, mag01), where `/run/user/1000/docker.sock` does not exist. So we
# ask the daemon what it is: `SecurityOptions` contains `name=rootless` only for a rootless
# daemon. On every other host this script is a no-op and prints why.
#
# Usage (in a workflow step, before any Maven/Testcontainers invocation):
#   run: bash scripts/ci-testcontainers-socket.sh >> "$GITHUB_ENV"
# It prints either nothing, or a single `KEY=value` line for $GITHUB_ENV.
#
# Diagnostics go to STDERR on purpose — stdout is consumed as $GITHUB_ENV.
set -uo pipefail

log() { echo "ci-testcontainers-socket: $*" >&2; }

if ! command -v docker >/dev/null 2>&1; then
  log "docker CLI unavailable — nothing to do"
  exit 0
fi

security_options="$(docker info --format '{{json .SecurityOptions}}' 2>/dev/null || echo '')"
if ! printf '%s' "$security_options" | grep -q 'rootless'; then
  log "daemon is not rootless — Testcontainers' default /var/run/docker.sock is correct"
  exit 0
fi

# Rootless dind: the socket lives under the daemon user's XDG runtime dir,
# /run/user/<uid>/docker.sock. uid 1000 is the right answer on these slots — the slot file
# pins `user: "1000:1000"` and docker:*-dind-rootless runs as uid 1000 by default.
# The probe below is best-effort only and, measured, does NOT fire for this image:
# DockerRootDir reports /home/rootless/.local/share/docker, which carries no /run/user path.
# It is kept for daemons that DO expose one; everything here relies on the 1000 fallback.
# If a slot ever runs the dind as another uid, this resolves to a path that does not exist
# and Ryuk fails exactly as it does today — same symptom, and this comment is the map.
uid="$(docker info --format '{{.DockerRootDir}}' 2>/dev/null | grep -oE '/run/user/[0-9]+' | grep -oE '[0-9]+' || true)"
socket="/run/user/${uid:-1000}/docker.sock"

log "rootless daemon detected — pointing Ryuk at ${socket}"
echo "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=${socket}"
