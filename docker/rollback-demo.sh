#!/usr/bin/env bash
# Roll pi.naumann.cloud back to a PRIOR deploy's exact pinned digest pair (issue #92).
#
# Usage:
#   docker/rollback-demo.sh <demo-tag>       # e.g. demo-2026-07-12-a1b2c3d
#   docker/rollback-demo.sh --list           # show recent demo deploy tags, newest first
#
# Unlike deploy-demo.sh (which RESOLVES a tag's CURRENT digest — wrong for rollback, since a
# floating tag like `edge` moves), this restores the exact PI_BFF_DIGEST/PI_WEB_DIGEST pair
# git already recorded for that tag's commit — no re-resolution, no ambiguity about "which
# build was that again". See RUNBOOK.md §8 for the drilled procedure this implements.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/docker/docker-compose.demo.yml"
ENV_FILE="$REPO_ROOT/docker/.env.demo"

if [[ "${1:-}" == "--list" ]]; then
  git -C "$REPO_ROOT" tag -l 'demo-*' --sort=-creatordate | head -20
  exit 0
fi

TAG="${1:?usage: docker/rollback-demo.sh <demo-tag>  (docker/rollback-demo.sh --list to see options)}"

if ! git -C "$REPO_ROOT" rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "no such tag: $TAG (try docker/rollback-demo.sh --list)" >&2
  exit 1
fi

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
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d

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
