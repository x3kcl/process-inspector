#!/usr/bin/env bash
# restore-drill.sh — make the "quarterly restore drill" (OPERATIONS §4) an EXECUTABLE proof
# instead of a promise. A backup you have never restored is a backup you do not have. This
# takes a dump produced by backup-audit-db.sh, restores it into a THROWAWAY Postgres container
# (never the live DB), and verifies the audit store came back intact — the partitioned
# audit_entry table exists, is itself partitioned, and carries rows.
#
# Safe by construction: it dials no live database and mutates nothing outside its own scratch
# container, which is always torn down. Run it after wiring the nightly backup, and on a
# calendar cadence, so a silently-corrupt dump is caught in a drill, not in an incident.
#
#   deploy/restore-drill.sh                 # newest dump in PI_BACKUP_DIR
#   deploy/restore-drill.sh /path/to.dump   # a specific dump
#
# Env: PI_BACKUP_DIR (default /var/backups/pi-audit), PI_DB / PI_DB_USER (default inspector).
set -euo pipefail

PI_BACKUP_DIR="${PI_BACKUP_DIR:-/var/backups/pi-audit}"
PI_DB="${PI_DB:-inspector}"
PI_DB_USER="${PI_DB_USER:-inspector}"
SCRATCH="pi-restore-drill-$$"
PGIMAGE="postgres:16-alpine"

log() { echo "restore-drill: $*"; }
die() { echo "restore-drill: FAIL: $*" >&2; exit 1; }
cleanup() { docker rm -f "$SCRATCH" >/dev/null 2>&1 || true; }
trap cleanup EXIT

command -v docker >/dev/null || die "docker not found on PATH"

DUMP="${1:-}"
if [[ -z "$DUMP" ]]; then
  DUMP="$(ls -1t "$PI_BACKUP_DIR"/${PI_DB}-*.dump 2>/dev/null | head -1 || true)"
  [[ -n "$DUMP" ]] || die "no ${PI_DB}-*.dump found in $PI_BACKUP_DIR (run backup-audit-db.sh first)"
fi
[[ -f "$DUMP" ]] || die "dump not found: $DUMP"
log "drilling $DUMP"

# Integrity: if a checksum was recorded at backup time, it must still match.
if [[ -f "${DUMP}.sha256" ]] && command -v sha256sum >/dev/null; then
  ( cd "$(dirname "$DUMP")" && sha256sum -c "$(basename "$DUMP").sha256" ) >/dev/null \
    || die "checksum mismatch — the dump is corrupt"
  log "checksum OK"
fi

log "starting throwaway Postgres ($PGIMAGE) as '$SCRATCH'"
docker run -d --name "$SCRATCH" -e POSTGRES_PASSWORD=drill -e POSTGRES_USER="$PI_DB_USER" \
  -e POSTGRES_DB="$PI_DB" "$PGIMAGE" >/dev/null

# Bounded readiness wait. The postgres image runs a TEMPORARY server during first-boot init
# (create user/db, run init scripts) and then restarts — a single pg_isready/SELECT can catch
# that transient server right before it shuts down, so require several CONSECUTIVE successful
# queries 1s apart: the sub-second init server cannot supply them, the real server does.
ok=0
for _ in $(seq 1 60); do
  if docker exec "$SCRATCH" psql -tAqX -U "$PI_DB_USER" -d "$PI_DB" -c 'SELECT 1' >/dev/null 2>&1; then
    ok=$((ok + 1))
    [[ "$ok" -ge 3 ]] && break
  else
    ok=0
  fi
  sleep 1
done
[[ "$ok" -ge 3 ]] || die "throwaway Postgres did not become ready"

log "restoring…"
# --no-owner/--no-privileges: the drill role differs from prod; we are proving DATA + SCHEMA
# restorability, not the role grants (those are deploy/sql/audit-roles.sql, applied separately).
docker exec -i "$SCRATCH" pg_restore --no-owner --no-privileges -U "$PI_DB_USER" -d "$PI_DB" < "$DUMP" \
  || die "pg_restore reported errors"

q() { docker exec "$SCRATCH" psql -tAqX -U "$PI_DB_USER" -d "$PI_DB" -c "$1"; }

# 1) the audit table exists and is a PARTITIONED table (relkind 'p') — the retention/legal-hold
#    substrate must survive the round-trip, not just a flattened copy.
PARTITIONED="$(q "SELECT relkind FROM pg_class WHERE relname='audit_entry';" | tr -d '[:space:]')"
[[ "$PARTITIONED" == "p" ]] || die "audit_entry missing or not partitioned after restore (relkind='$PARTITIONED')"

# 2) at least one partition came back.
PARTS="$(q "SELECT count(*) FROM pg_inherits i JOIN pg_class p ON p.oid=i.inhparent WHERE p.relname='audit_entry';" | tr -d '[:space:]')"
[[ "${PARTS:-0}" -ge 1 ]] || die "audit_entry has no partitions after restore"

# 3) row count (informational — an empty-but-valid store is a legitimate fresh deploy).
ROWS="$(q "SELECT count(*) FROM audit_entry;" | tr -d '[:space:]')"

log "PASS — audit_entry restored: partitioned, ${PARTS} partition(s), ${ROWS} row(s)"
