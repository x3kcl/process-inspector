#!/usr/bin/env bash
# Deploy (or roll back, via an older IMAGE_TAG) pi.naumann.cloud to a digest-pinned image
# build.
#
# Usage:
#   docker/deploy-demo.sh [IMAGE_TAG]              # default: edge. Also sha-<short7>/X.Y.Z
#                                                   # (the published IMAGE tag has no "v" —
#                                                   # docker/metadata-action strips it from
#                                                   # the vX.Y.Z git/release tag; issue #200).
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
# procedure). This script only ever RESOLVES a tag's CURRENT digest, which is only safe going
# forward — rollback-demo.sh is the one that's correct for "go back to exactly what was
# running before" (a floating tag like `edge` may have moved since).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/docker/docker-compose.demo.yml"
ENV_FILE="$REPO_ROOT/docker/.env.demo"
BFF_IMAGE="ghcr.io/x3kcl/process-inspector-bff"
WEB_IMAGE="ghcr.io/x3kcl/process-inspector-web"
DIGEST_RE='^sha256:[0-9a-f]{64}$'

DRY_RUN=0
if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=1
  shift
fi
IMAGE_TAG="${1:-edge}"

# The published image tag never carries the git/release tag's leading "v" (docker/
# metadata-action's {{version}} pattern strips it) — a "vX.Y.Z" IMAGE_TAG will always 404
# regardless of whether that version actually published, which is exactly what produced
# issue #200's false "images never published" report. Catch the mistake with a clear
# pointer to the fix instead of a generic registry "not found".
if [[ "$IMAGE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+ ]]; then
  echo "IMAGE_TAG '$IMAGE_TAG' looks like a git/release tag, not a published image tag." >&2
  echo "Published image tags drop the leading 'v' — try '${IMAGE_TAG#v}' instead." >&2
  exit 1
fi

resolve_digest() {
  local digest
  digest="$(docker buildx imagetools inspect "$1:$IMAGE_TAG" --format '{{json .Manifest}}' | jq -r '.digest')"
  # jq -r prints the literal string "null" (exit 0) if .digest is absent — plain `set -e`
  # would not catch that, and a "null" digest would otherwise be written straight into
  # docker/.env.demo and only fail much later, opaquely, at `docker compose pull`.
  if [[ ! "$digest" =~ $DIGEST_RE ]]; then
    echo "resolved digest for $1:$IMAGE_TAG doesn't look like a digest: '$digest'" >&2
    exit 1
  fi
  echo "$digest"
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
# Scoped to the two services this script actually manages — NOT a blanket `up -d`. Since
# issue #201, docker-compose.demo.yml's `postgres` service carries WAL-archiving config
# (`command:` override) that isn't yet applied to the live container; an unscoped `up -d`
# would detect that config drift on THIS routine digest-bump deploy and silently recreate
# (restart) postgres as a side effect — exactly the un-deliberate activation issue #201's own
# docs say must not happen. Activating it is a separate, explicit `up -d postgres` step (see
# deploy/README.md "Activating WAL archiving").
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d backend frontend

echo "Verifying (expect 401 = chain healthy)..."
sleep 5
CODE="$(curl -s -o /dev/null -w '%{http_code}' https://pi.naumann.cloud/api/engines || echo "curl-failed")"
echo "  https://pi.naumann.cloud/api/engines -> $CODE"
if [[ "$CODE" != "401" ]]; then
  # Fail BEFORE committing — an unverified deploy must never become "the" attribution
  # record. Containers are already running the new images; docker/.env.demo is left
  # modified-but-uncommitted for a human to inspect (git diff still shows the intended
  # pin), same posture as rollback-demo.sh's identical check.
  echo "ERROR: expected 401, got $CODE — see docker/DEMO-DEPLOY.md#troubleshooting. Not committing/tagging this deploy." >&2
  exit 1
fi

git -C "$REPO_ROOT" add docker/.env.demo
if git -C "$REPO_ROOT" diff --cached --quiet -- docker/.env.demo; then
  echo "docker/.env.demo unchanged — '$IMAGE_TAG' already resolves to what's currently pinned. Nothing to commit/tag."
  exit 0
fi
git -C "$REPO_ROOT" commit -m "chore(demo): deploy $IMAGE_TAG (bff@${BFF_DIGEST:7:12} web@${WEB_DIGEST:7:12})"
# Tag AFTER the commit, from the commit it actually names — computing this before the
# commit would embed the PARENT sha in a tag that resolves to the new commit.
TAG="demo-$(date +%Y-%m-%d)-$(git -C "$REPO_ROOT" rev-parse --short HEAD)"
git -C "$REPO_ROOT" tag -a "$TAG" -m "demo deploy: $IMAGE_TAG"

cat <<EOF

Deployed and committed locally as $TAG. To publish the attribution record:
  git -C "$REPO_ROOT" push origin HEAD "$TAG"

To roll back to exactly this state later:
  docker/rollback-demo.sh $TAG
EOF
