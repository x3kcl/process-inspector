#!/usr/bin/env bash
# Run THIS inside Azure Cloud Shell (https://shell.azure.com) — browser MFA works there.
# Device-code `az login` is blocked by Entra security defaults; Cloud Shell is already authed.
#
# Paste:
#   curl -fsSL https://raw.githubusercontent.com/x3kcl/process-inspector/cursor/azure-free-deploy-3ab7/deploy/azure/cloud-shell-deploy.sh | bash
#
# Or:
#   git clone --depth 1 --branch cursor/azure-free-deploy-3ab7 \
#     https://github.com/x3kcl/process-inspector.git && \
#     bash process-inspector/deploy/azure/cloud-shell-deploy.sh
set -euo pipefail

BRANCH="${PI_GIT_REF:-cursor/azure-free-deploy-3ab7}"
WORKDIR="${HOME}/process-inspector-azure"

need() { command -v "$1" >/dev/null || { echo "missing: $1" >&2; exit 1; }; }
need az
need openssl
need git

if ! az account show >/dev/null 2>&1; then
  echo "Cloud Shell is not signed in to a subscription. Pick one in the shell banner, or:" >&2
  echo "  az login   # browser flow inside Cloud Shell" >&2
  echo "  az account list -o table && az account set --subscription <id>" >&2
  exit 1
fi

az account show -o table
echo

if [[ ! -d "$WORKDIR/.git" ]]; then
  rm -rf "$WORKDIR"
  git clone --depth 1 --branch "$BRANCH" \
    https://github.com/x3kcl/process-inspector.git "$WORKDIR"
else
  git -C "$WORKDIR" fetch origin "$BRANCH"
  git -C "$WORKDIR" checkout "$BRANCH"
  git -C "$WORKDIR" pull --ff-only origin "$BRANCH" || true
fi

export PI_GIT_REF="$BRANCH"
# Cloud Shell already has a real `az` — deploy.sh uses it.
exec "$WORKDIR/deploy/azure/deploy.sh"
