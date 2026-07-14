#!/usr/bin/env bash
# R-GOV-08 demand-trigger measurement (issue #106 S0 slice) — thin psql wrapper around
# mine-playbook-demand.sql. Read-only; safe to re-run at any time, e.g. periodically against
# real pilot production audit data once it exists (current dev audit_entry has no pilot
# history to evaluate the gate against — see docs/reviews/S0-PLAYBOOK-DEMAND-2026-07.md).
#
# Usage:
#   scripts/mine-playbook-demand.sh                 # uses $INSPECTOR_DB_URI or the dev default
#   INSPECTOR_DB_URI=postgresql://user:pass@host:port/db scripts/mine-playbook-demand.sh
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
DB_URI="${INSPECTOR_DB_URI:-postgresql://inspector:inspector@localhost:5433/inspector}"
exec psql "$DB_URI" -f "$DIR/mine-playbook-demand.sql"
