#!/usr/bin/env bash
# Deploy (or roll back, via an older IMAGE_TAG) pi.naumann.cloud to a digest-pinned image
# build.
#
# Usage:
#   docker/deploy-demo.sh [IMAGE_TAG]              # default: edge. Also sha-<short7>/vX.Y.Z.
#   docker/deploy-demo.sh --dry-run [IMAGE_TAG]     # resolve + print, no compose/commit/tag
#
# What it does (issue #92 — demo compose pinned by digest, never a floating tag, and every
# deploy attributable to a SHA):
#   1. Resolves the current digest of IMAGE_TAG for both published images via
#      `docker buildx imagetools inspect` (reads registry metadata only — no pull).
#   2. Writes PI_BFF_DIGEST/PI_WEB_DIGEST into docker/.env.demo.
#   3. `docker compose ... pull && up -d` — the running containers now match that digest.
#   4. Commits docker/.env.demo and tags the commit `demo-YYYY-MM-DD-<shortsha>` — the git
#      history of that one file is the attribution record for "what's running right now".
#      Does NOT push (see the printed instructions) — publishing the tag/commit is a
#      separate, deliberate step.
#
# Rollback = docker/rollback-demo.sh <demo-tag> (restores a PRIOR deploy's exact pinned
# digest pair from git history — see that script and RUNBOOK.md §8 for the drilled
# procedure). Re-running THIS script with an older `sha-<short7>` re-resolves that tag's
# CURRENT digest, which is only safe if the registry never re-tags — rollback-demo.sh is the
# one that's actually correct for "go back to exactly what was running before".
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/docker/docker-compose.demo.yml"
ENV_FILE="$REPO_ROOT/docker/.env.demo"
BFF_IMAGE="ghcr.io/x3kcl/process-inspector-bff"
WEB_IMAGE="ghcr.io/x3kcl/process-inspector-web"

DRY_RUN=0
if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=1
  shift
fi
IMAGE_TAG="${1:-edge}"

resolve_digest() {
  local ref="$1:$IMAGE_TAG"
  # A raw sha256:... IMAGE_TAG is already a digest — pass it through unresolved.
  if [[ "$IMAGE_TAG" == sha256:* ]]; then
    echo "$IMAGE_TAG"
    return
  fi
  docker buildx imagetools inspect "$ref" --format '{{json .Manifest}}' | jq -r '.digest'
}

echo "Resolving digests for tag '$IMAGE_TAG'..."
BFF_DIGEST="$(resolve_digest "$BFF_IMAGE")"
WEB_DIGEST="$(resolve_digest "$WEB_IMAGE")"
echo "  $BFF_IMAGE@$BFF_DIGEST"
echo "  $WEB_IMAGE@$WEB_DIGEST"

if [[ "$DRY_RUN" == "1" ]]; then
  echo "(--dry-run: not touching docker/.env.demo, not deploying, not tagging)"
  exit 0
fi

sed -i.bak \
  -e "s|^PI_BFF_DIGEST=.*|PI_BFF_DIGEST=$BFF_DIGEST|" \
  -e "s|^PI_WEB_DIGEST=.*|PI_WEB_DIGEST=$WEB_DIGEST|" \
  "$ENV_FILE"
rm -f "$ENV_FILE.bak"

echo "Pulling + redeploying..."
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" pull backend frontend
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d

echo "Verifying (expect 401 = chain healthy)..."
sleep 5
CODE="$(curl -s -o /dev/null -w '%{http_code}' https://pi.naumann.cloud/api/engines || echo "curl-failed")"
echo "  https://pi.naumann.cloud/api/engines -> $CODE"
if [[ "$CODE" != "401" ]]; then
  echo "WARNING: expected 401, got $CODE — see docker/DEMO-DEPLOY.md#troubleshooting before walking away." >&2
fi

TAG="demo-$(date +%Y-%m-%d)-$(git -C "$REPO_ROOT" rev-parse --short HEAD)"
git -C "$REPO_ROOT" add docker/.env.demo
git -C "$REPO_ROOT" commit -m "chore(demo): deploy $IMAGE_TAG (bff@${BFF_DIGEST:7:12} web@${WEB_DIGEST:7:12})"
git -C "$REPO_ROOT" tag -a "$TAG" -m "demo deploy: $IMAGE_TAG"

cat <<EOF

Deployed and committed locally as $TAG. To publish the attribution record:
  git -C "$REPO_ROOT" push origin HEAD "$TAG"

To roll back to exactly this state later:
  docker/rollback-demo.sh $TAG
EOF
