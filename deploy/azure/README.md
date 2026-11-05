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
2. `az` CLI on the machine that runs `deploy.sh` (Docker wrapper is fine — see below).
3. Login + subscription selected.

```bash
az login --use-device-code
az account list -o table
az account set --subscription "<name-or-id>"
```

## Deploy

From the repo root:

```bash
deploy/azure/deploy.sh
# optional overrides:
#   AZ_LOCATION=switzerlandnorth AZ_VM_SIZE=Standard_B2ms PI_VERSION=latest \
#   PI_GIT_REF=main deploy/azure/deploy.sh
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
