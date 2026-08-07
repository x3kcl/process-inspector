#!/usr/bin/env bash
# Roll pi.naumann.cloud back to a PRIOR deploy's exact pinned digest pair (issue #92).
#
# Usage:
#   docker/rollback-demo.sh <demo-tag>       # e.g. demo-2026-07-12-a1b2c3d
#   docker/rollback-demo.sh --list           # show recent demo deploy tags, newest first
#   docker/rollback-demo.sh --allow-engine-recreate <demo-tag>
#                                             # override the issue #377 engine-recreate guard
#                                             # below (see deploy-demo.sh's identical guard)
#
# Unlike deploy-demo.sh (which RESOLVES a tag's CURRENT digest — wrong for rollback, since a
# floating tag like `edge` moves), this restores the exact PI_BFF_DIGEST/PI_WEB_DIGEST pair
# git already recorded for that tag's commit — no re-resolution, no ambiguity about "which
# build was that again". See RUNBOOK.md §8 for the drilled procedure this implements.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/docker/docker-compose.demo.yml"
ENV_FILE="$REPO_ROOT/docker/.env.demo"

# issue #377 — same engine-recreate guard as deploy-demo.sh (see that script's header comment
# for the full incident rationale). Neither `up` call below targets the engines today; this
# guards against a future edit widening one of them.
ENGINE_SERVICES=(engine-a engine-b engine-7)
ALLOW_ENGINE_RECREATE=0

# issue #370 — same TOPOLOGY-DRIFT guard as deploy-demo.sh, and for the same reason: this
# script also rewrites docker/.env.demo (the attribution record) and its `up` calls are
# service-scoped, so a topology mismatch would be silently carried into a "successful"
# rollback. If anything, drift is MORE likely here: the tag being restored may predate a
# service that exists today, or postdate one that has since been removed.
TOPOLOGY_DRIFT_OK=0

assert_no_topology_drift() {
  [[ "$TOPOLOGY_DRIFT_OK" == 1 ]] && return 0
  local declared running missing extra
  declared="$(docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" config --services 2>/dev/null | sort)"
  running="$(docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" ps --services --all 2>/dev/null | sort)"
  [[ -n "$declared" ]] || die "could not read the compose service list — refusing to roll back blind"
  missing="$(comm -23 <(printf '%s\n' "$declared") <(printf '%s\n' "$running") | tr '\n' ' ' | sed 's/ *$//')"
  extra="$(comm -13 <(printf '%s\n' "$declared") <(printf '%s\n' "$running") | tr '\n' ' ' | sed 's/ *$//')"
  if [[ -n "$missing" || -n "$extra" ]]; then
    echo "REFUSING: the running topology does not match $COMPOSE_FILE." >&2
    [[ -n "$missing" ]] && echo "  declared but NOT created: $missing" >&2
    [[ -n "$extra" ]] && echo "  running but no longer declared: $extra" >&2
    echo "" >&2
    echo "This script restores a prior digest pair; it does NOT create or remove services" >&2
    echo "(issue #370). Reconcile the topology deliberately first — and read the seed" >&2
    echo "non-idempotency and DNS-alias hazards in deploy-demo.sh's guard note before" >&2
    echo "reaching for a bare 'up -d'. Override with --allow-topology-drift." >&2
    exit 1
  fi
}

compose_up_guarded() {
  local -a services=()
  local arg svc engine
  for arg in "$@"; do
    case "$arg" in
      -*) ;;
      *) services+=("$arg") ;;
    esac
  done
  if [[ "$ALLOW_ENGINE_RECREATE" != "1" ]]; then
    if [[ "${#services[@]}" -eq 0 ]]; then
      echo "REFUSING: 'docker compose up' with no service list recreates EVERY service," >&2
      echo "including the engines (issue #377). Pass --allow-engine-recreate to override." >&2
      exit 1
    fi
    for svc in "${services[@]}"; do
      for engine in "${ENGINE_SERVICES[@]}"; do
        if [[ "$svc" == "$engine" ]]; then
          echo "REFUSING: this would recreate '$engine'." >&2
          echo "flowable-rest keeps its process/job/history state in a container-scoped H2" >&2
          echo "store on a named volume (docker-compose.demo.yml, issue #377) — a rollback" >&2
          echo "should never touch it as a side effect. Pass --allow-engine-recreate if" >&2
          echo "recreating '$engine' is actually intended." >&2
          exit 1
        fi
      done
    done
  fi
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up "$@"
}

while [[ "${1:-}" == --* ]]; do
  case "$1" in
    --allow-engine-recreate) ALLOW_ENGINE_RECREATE=1 ;;
    --allow-topology-drift)  TOPOLOGY_DRIFT_OK=1 ;;
    --list) break ;;
    *) echo "unknown flag: $1" >&2; exit 1 ;;
  esac
  shift
done

if [[ "${1:-}" == "--list" ]]; then
  git -C "$REPO_ROOT" tag -l 'demo-*' --sort=-creatordate | head -20
  exit 0
fi

TAG="${1:?usage: docker/rollback-demo.sh [--allow-engine-recreate] <demo-tag>  (docker/rollback-demo.sh --list to see options)}"

if ! git -C "$REPO_ROOT" rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "no such tag: $TAG (try docker/rollback-demo.sh --list)" >&2
  exit 1
fi

# #370: refuse BEFORE touching .env.demo — it is the attribution record.
assert_no_topology_drift

echo "Restoring docker/.env.demo from $TAG..."
# Resolve into a temp file first — `> "$ENV_FILE"` truncates the target as part of shell
# redirection setup, BEFORE `git show`'s exit status is known, so a failing `git show`
# (e.g. the tag exists but somehow doesn't carry this path) would otherwise leave
# docker/.env.demo silently empty rather than failing loudly with the old content intact.
TMP_ENV="$(mktemp)"
trap 'rm -f "$TMP_ENV"' EXIT
git -C "$REPO_ROOT" show "$TAG:docker/.env.demo" > "$TMP_ENV"
grep -E '^PI_(BFF|WEB)_DIGEST=' "$TMP_ENV"
mv "$TMP_ENV" "$ENV_FILE"
trap - EXIT

echo "Pulling + redeploying..."
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" pull backend frontend
# `postgres` stays excluded — see deploy-demo.sh's identical comment (issue #201): an
# unscoped `up -d` would also reconcile `postgres`'s `command:`/pg_hba drift, turning a
# routine rollback into an unintended Postgres restart. The three Docker-native backup
# sidecars (issue #201-followup) ARE included with --force-recreate, same reasoning AND same
# fix as deploy-demo.sh: they carry none of postgres's restart risk, and --force-recreate is
# needed because their bind-mounted scripts don't change the compose config hash on their own.
compose_up_guarded -d backend frontend
compose_up_guarded -d --force-recreate audit-backup audit-basebackup wal-receiver

echo "Verifying (expect 401 = chain healthy)..."
sleep 5
CODE="$(curl -s -o /dev/null -w '%{http_code}' https://pi.naumann.cloud/api/engines || echo "curl-failed")"
echo "  https://pi.naumann.cloud/api/engines -> $CODE"
if [[ "$CODE" != "401" ]]; then
  echo "WARNING: expected 401, got $CODE — see docker/DEMO-DEPLOY.md#troubleshooting." >&2
  exit 1
fi

git -C "$REPO_ROOT" add docker/.env.demo
if git -C "$REPO_ROOT" diff --cached --quiet -- docker/.env.demo; then
  echo "docker/.env.demo unchanged — already at $TAG's pinned digests. Nothing to commit."
  exit 0
fi
git -C "$REPO_ROOT" commit -m "chore(demo): roll back to $TAG"
echo
echo "Rolled back and committed locally. Publish with: git -C \"$REPO_ROOT\" push origin HEAD"
