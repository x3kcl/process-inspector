#!/usr/bin/env bash
# Provision a Process Inspector trial VM on an Azure free/credit subscription.
#
# Prerequisites: `az` logged in (`az login --use-device-code`), a subscription selected.
#
# Usage:
#   deploy/azure/deploy.sh              # create / update
#   deploy/azure/deploy.sh --destroy    # delete the resource group
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
RG="${AZ_RG:-rg-process-inspector}"
LOC="${AZ_LOCATION:-westeurope}"
VM_NAME="${AZ_VM_NAME:-pi-trial}"
# B1s (1 GiB, always-free hours) is too small. B2ms (~8 GiB) fits the slim stack and
# burns the $200 free-account credit — stop/destroy when done.
SIZE="${AZ_VM_SIZE:-Standard_B2ms}"
ADMIN_USER="${AZ_ADMIN_USER:-azureuser}"
PI_VERSION="${PI_VERSION:-latest}"
# Branch/tag the VM clones. Default: main (public). Override to test this PR's files.
PI_GIT_REF="${PI_GIT_REF:-main}"

need() { command -v "$1" >/dev/null || { echo "missing: $1" >&2; exit 1; }; }
need az
need openssl
need python3

if [[ "${1:-}" == "--destroy" ]]; then
  echo "Deleting resource group $RG ..."
  az group delete --name "$RG" --yes --no-wait
  echo "Delete started (async). Monitor: az group show -n $RG"
  exit 0
fi

if ! az account show >/dev/null 2>&1; then
  echo "Not logged in. Run: az login --use-device-code" >&2
  exit 1
fi

SUB="$(az account show --query id -o tsv)"
echo "Subscription: $(az account show --query name -o tsv) ($SUB)"
echo "Region=$LOC RG=$RG VM=$VM_NAME Size=$SIZE"

# Password for ladder users (viewer/responder/operator/admin). Persist beside the script
# so re-runs keep the same value; never print it after first create.
SECRET_FILE="${ROOT}/.trial-secrets"
if [[ -f "$SECRET_FILE" ]]; then
  # shellcheck disable=SC1090
  . "$SECRET_FILE"
else
  INSPECTOR_DEV_PASSWORD="$(openssl rand -base64 18 | tr -d '/+=' | head -c 20)"
  POSTGRES_PASSWORD="$(openssl rand -base64 18 | tr -d '/+=' | head -c 20)"
  umask 077
  cat >"$SECRET_FILE" <<EOF
INSPECTOR_DEV_PASSWORD=$INSPECTOR_DEV_PASSWORD
POSTGRES_PASSWORD=$POSTGRES_PASSWORD
EOF
  echo "Wrote $SECRET_FILE (gitignored). Sign-in password for ladder users stored there."
fi

CLOUD_INIT="$(mktemp)"
trap 'rm -f "$CLOUD_INIT"' EXIT
sed \
  -e "s|__INSPECTOR_DEV_PASSWORD__|${INSPECTOR_DEV_PASSWORD}|g" \
  -e "s|__POSTGRES_PASSWORD__|${POSTGRES_PASSWORD}|g" \
  -e "s|__PI_VERSION__|${PI_VERSION}|g" \
  -e "s|__PI_GIT_REF__|${PI_GIT_REF}|g" \
  "$ROOT/cloud-init.yml.tmpl" >"$CLOUD_INIT"

if ! az group show --name "$RG" >/dev/null 2>&1; then
  az group create --name "$RG" --location "$LOC" --output none
fi

if az vm show --resource-group "$RG" --name "$VM_NAME" >/dev/null 2>&1; then
  echo "VM $VM_NAME already exists — printing endpoints only."
else
  echo "Creating VM (Ubuntu 24.04, Docker via cloud-init) ..."
  az vm create \
    --resource-group "$RG" \
    --name "$VM_NAME" \
    --image Canonical:ubuntu-24_04-lts:server:latest \
    --size "$SIZE" \
    --admin-username "$ADMIN_USER" \
    --generate-ssh-keys \
    --public-ip-sku Standard \
    --nsg-rule SSH \
    --custom-data "$CLOUD_INIT" \
    --output none

  echo "Opening HTTP :80 ..."
  az vm open-port --resource-group "$RG" --name "$VM_NAME" --port 80 --priority 1001 --output none
fi

IP="$(az vm show -d -g "$RG" -n "$VM_NAME" --query publicIps -o tsv)"
echo
echo "VM public IP: $IP"
echo "UI (after ~5–10 min first boot while images pull): http://$IP/"
echo "Sign-in users: viewer | responder | operator | admin"
echo "Password: see $SECRET_FILE (INSPECTOR_DEV_PASSWORD)"
echo "Boot log on VM: ssh $ADMIN_USER@$IP 'sudo tail -f /var/log/process-inspector-boot.log'"
echo "Destroy later: $0 --destroy"
