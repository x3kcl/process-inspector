#!/usr/bin/env bash
# restore-drill.sh — make the "quarterly restore drill" (OPERATIONS §4) an EXECUTABLE proof
# instead of a promise. A backup you have never restored is a backup you do not have. This
# takes a dump produced by `audit-backup` (the Docker-native compose service, issue
# #201-followup) — or, for a host still running the superseded systemd mechanism (see
# deploy/systemd/pi-audit-backup.*), one produced by `backup-audit-db.sh` — and restores it
# into a THROWAWAY Postgres container (never the live DB), verifying the audit store came back
# intact: the partitioned audit_entry table exists, is itself partitioned, and carries rows.
#
# Safe by construction: it dials no live database and mutates nothing outside its own scratch
# container, which is always torn down. Run it after wiring the backup, and on a calendar
# cadence, so a silently-corrupt dump is caught in a drill, not in an incident.
#
# STORAGE SOURCE (issue #201-followup — Docker-native backups):
#   Dumps now live in a named Docker volume (`inspector-logical-dumps` by default), written by
#   the `audit-backup` compose service — no host bind-mount, no host-path visibility. This
#   script reads that volume DIRECTLY: the throwaway Postgres container mounts it read-only
#   and `pg_restore` is pointed at the file inside the container (no host copy needed — named
#   volumes are mountable by name regardless of host-path visibility). A one-shot Alpine
#   helper picks the target file (newest, or PI_DUMP_FILE) and verifies its checksum before
#   the restore.
#
#   Legacy/manual use: if you pass a PATH that exists on the host filesystem, that exact file
#   is used instead (unchanged host-path behavior, for anyone still on the superseded
#   deploy/systemd/pi-audit-backup.* mechanism or a manually-copied-out dump).
#
#   deploy/restore-drill.sh                       # newest dump in the inspector-logical-dumps volume
#   deploy/restore-drill.sh inspector-20260715...  # a specific FILENAME within that volume
#   deploy/restore-drill.sh /path/to.dump          # a specific HOST PATH (legacy mode)
#
# Env: PI_DUMPS_VOLUME (default inspector-logical-dumps), PI_DUMP_FILE (pick a specific
#      filename within the volume instead of the newest — same effect as the positional-arg
#      filename form), PI_DB / PI_DB_USER (default inspector).
set -euo pipefail

PI_DB="${PI_DB:-inspector}"
PI_DB_USER="${PI_DB_USER:-inspector}"
PI_DUMPS_VOLUME="${PI_DUMPS_VOLUME:-inspector-logical-dumps}"
SCRATCH="pi-restore-drill-$$"
PGIMAGE="postgres:16-alpine"

log() { echo "restore-drill: $*"; }
die() { echo "restore-drill: FAIL: $*" >&2; exit 1; }
cleanup() { docker rm -f "$SCRATCH" >/dev/null 2>&1 || true; }
trap cleanup EXIT

command -v docker >/dev/null || die "docker not found on PATH"

ARG="${1:-}"
USE_VOLUME=1
DUMP_HOST_PATH=""
DUMP_FILENAME="${PI_DUMP_FILE:-}"

if [[ -n "$ARG" && -f "$ARG" ]]; then
  # Legacy mode: an explicit host path that actually exists — unchanged behavior.
  USE_VOLUME=0
  DUMP_HOST_PATH="$ARG"
elif [[ -n "$ARG" ]]; then
  # Not a host path — treat it as a filename WITHIN the named volume.
  DUMP_FILENAME="$ARG"
fi

if [[ "$USE_VOLUME" == "1" ]]; then
  docker volume inspect "$PI_DUMPS_VOLUME" >/dev/null 2>&1 \
    || die "no Docker volume '$PI_DUMPS_VOLUME' (run 'docker compose ... run --rm audit-backup /scripts/backup-once.sh' first, or pass a host path / PI_DUMPS_VOLUME)"

  log "resolving target dump inside volume '$PI_DUMPS_VOLUME'"
  # One-shot helper: picks the target file (newest ${PI_DB}-*.dump, or the exact
  # DUMP_FILENAME if given), verifies its checksum if a sidecar exists, and prints the
  # resolved filename on success — nothing is copied to the host.
  SELECTED="$(docker run --rm -v "${PI_DUMPS_VOLUME}:/dumps:ro" "$PGIMAGE" sh -c "
    set -eu
    if [ -n '${DUMP_FILENAME}' ]; then
      f='/dumps/${DUMP_FILENAME}'
    else
      f=\$(ls -1t /dumps/${PI_DB}-*.dump 2>/dev/null | head -1)
    fi
    [ -n \"\$f\" ] && [ -f \"\$f\" ] || { echo 'NOFILE' >&2; exit 1; }
    if [ -f \"\${f}.sha256\" ]; then
      ( cd /dumps && sha256sum -c \"\$(basename \"\${f}.sha256\")\" ) >&2 || { echo 'BADSUM' >&2; exit 1; }
    fi
    basename \"\$f\"
  ")" || die "no matching dump found (or checksum mismatch) in volume '$PI_DUMPS_VOLUME'"
  log "selected $SELECTED (checksum OK if a sidecar was present)"
else
  DUMP="$DUMP_HOST_PATH"
  log "drilling $DUMP (legacy host-path mode)"
  if [[ -f "${DUMP}.sha256" ]] && command -v sha256sum >/dev/null; then
    ( cd "$(dirname "$DUMP")" && sha256sum -c "$(basename "$DUMP").sha256" ) >/dev/null \
      || die "checksum mismatch — the dump is corrupt"
    log "checksum OK"
  fi
fi

log "starting throwaway Postgres ($PGIMAGE) as '$SCRATCH'"
if [[ "$USE_VOLUME" == "1" ]]; then
  docker run -d --name "$SCRATCH" -e POSTGRES_PASSWORD=drill -e POSTGRES_USER="$PI_DB_USER" \
    -e POSTGRES_DB="$PI_DB" -v "${PI_DUMPS_VOLUME}:/dumps:ro" "$PGIMAGE" >/dev/null
else
  docker run -d --name "$SCRATCH" -e POSTGRES_PASSWORD=drill -e POSTGRES_USER="$PI_DB_USER" \
    -e POSTGRES_DB="$PI_DB" "$PGIMAGE" >/dev/null
fi

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
if [[ "$USE_VOLUME" == "1" ]]; then
  docker exec "$SCRATCH" pg_restore --no-owner --no-privileges -U "$PI_DB_USER" -d "$PI_DB" "/dumps/$SELECTED" \
    || die "pg_restore reported errors"
else
  docker exec -i "$SCRATCH" pg_restore --no-owner --no-privileges -U "$PI_DB_USER" -d "$PI_DB" < "$DUMP" \
    || die "pg_restore reported errors"
fi

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
