# Azure free-account trial

Run Process Inspector on your Azure free / credit subscription as a single Linux VM
with Docker Compose (two demo Flowable engines + Postgres + BFF + web).

## Why a VM (not Container Apps / B1s)

| Option | Verdict |
|--------|---------|
| **Standard_B1s** (750 h/mo free) | **Too small** — 1 GiB RAM; stack needs ~4–6 GiB |
| **Standard_B2ms** (default here) | Fits slim stack; paid from the **$200 free credit** (or PAYG) |
| Container Apps free grant | Fine for scale-to-zero APIs; awkward for always-on Postgres + engines |

Stop or destroy the VM when idle so credit lasts.

## Prerequisites

1. Azure free account (or any subscription with quota in the target region).
2. A way to run `az` **that is not device-code login** if Entra **security defaults**
   are on — those defaults **block device code flow** (`AADSTS530035`). Prefer
   **Azure Cloud Shell** (below).

## Deploy (recommended): Azure Cloud Shell

Security defaults block `az login --use-device-code`. Cloud Shell is already signed
in via the browser (MFA works there).

1. Open https://shell.azure.com (Bash) while signed in as the subscription owner.
2. Confirm the free subscription is selected in the shell banner.
3. Paste:

```bash
curl -fsSL https://raw.githubusercontent.com/x3kcl/process-inspector/cursor/azure-free-deploy-3ab7/deploy/azure/cloud-shell-deploy.sh | bash
```

The script clones this branch and runs `deploy.sh` (creates `rg-process-inspector` +
a B2ms VM). It prints the public IP and writes passwords under
`~/process-inspector-azure/deploy/azure/.trial-secrets`.

## Deploy (alternate): local `az` after disabling security defaults

Only if you turned **Security defaults → Disabled** in Entra ID → Properties:

```bash
az login --use-device-code --tenant <your-tenant-id>
az account list -o table
az account set --subscription "<name-or-id>"
PI_GIT_REF=cursor/azure-free-deploy-3ab7 deploy/azure/deploy.sh
# optional: AZ_LOCATION=switzerlandnorth AZ_VM_SIZE=Standard_B2ms PI_VERSION=latest
```

First run writes `deploy/azure/.trial-secrets` (gitignored) with the ladder sign-in
password and Postgres password. Re-runs reuse it.

After cloud-init finishes (~5–10 minutes on a cold pull):

- UI: `http://<public-ip>/`
- Users: `viewer` / `responder` / `operator` / `admin`
- Password: `INSPECTOR_DEV_PASSWORD` in `.trial-secrets`

SSH: `ssh azureuser@<public-ip>` then
`sudo tail -f /var/log/process-inspector-boot.log`.

## Destroy

```bash
deploy/azure/deploy.sh --destroy
```

Deletes the whole resource group (`rg-process-inspector` by default).

## Cost / free-account notes

- Disk + public IP + B2ms burn **credit**, not the B1s free hours.
- Prefer `switzerlandnorth` / `westeurope` (set `AZ_LOCATION`) close to you.
- Spending limit: free accounts block overspend until you upgrade to PAYG — if
  `az vm create` fails on quota/spend, check Portal → Subscriptions → spending limit
  / free credit remaining.

## What this is / is not

- **Is**: a disposable trial of the published images + two throwaway Flowable REST
  engines, seeded like the local harness.
- **Is not**: the production hp04 demo (`docker/DEMO-DEPLOY.md`), TLS, OIDC, or a
  hardened network. Treat the public IP as a short-lived lab.
