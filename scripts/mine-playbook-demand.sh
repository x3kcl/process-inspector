#!/usr/bin/env bash
# R-GOV-08 demand-trigger measurement (issue #106 S0 slice) — thin psql wrapper around
# mine-playbook-demand.sql. Read-only; safe to re-run at any time, e.g. periodically against
# real pilot production audit data once it exists (current dev audit_entry has no pilot
# history to evaluate the gate against — see docs/reviews/S0-PLAYBOOK-DEMAND-2026-07.md).
#
# Usage:
#   scripts/mine-playbook-demand.sh                       # dev defaults below (PG* env vars)
#   INSPECTOR_DB_URI=postgresql://... scripts/mine-playbook-demand.sh   # full connection string
#   PGHOST=... PGPORT=... PGUSER=... PGPASSWORD=... PGDATABASE=... scripts/mine-playbook-demand.sh
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -n "${INSPECTOR_DB_URI:-}" ]; then
  exec psql "$INSPECTOR_DB_URI" -f "$DIR/mine-playbook-demand.sql"
fi
: "${PGHOST:=localhost}"
: "${PGPORT:=5433}"
: "${PGUSER:=inspector}"
: "${PGPASSWORD:=inspector}"
: "${PGDATABASE:=inspector}"
export PGHOST PGPORT PGUSER PGPASSWORD PGDATABASE
exec psql -f "$DIR/mine-playbook-demand.sql"
