#!/usr/bin/env bash
# pitr-drill.sh — makes point-in-time recovery an EXECUTABLE proof, mirroring
# restore-drill.sh's safe-by-construction pattern exactly: a THROWAWAY Postgres, never the
# live DB, torn down on exit. Where restore-drill.sh proves the nightly LOGICAL dump restores,
# this proves the newer PHYSICAL path (issue #201): a basebackup-audit-db.sh base backup,
# replayed forward through the continuously archived WAL segments (docker-compose.demo.yml's
# `postgres.command` archive_command target), up to either "as much as is available" (default)
# or an explicit --target-time.
#
# Recovery mechanism: Postgres 16's CURRENT approach — a `recovery.signal` marker file plus
# `restore_command` (+ optional `recovery_target_time`/`recovery_target_action`) appended to
# `postgresql.auto.conf` inside PGDATA. NOT the pre-12 `recovery.conf` file, which Postgres
# removed entirely (a server with a `recovery.conf` present today just refuses to start).
#
# Safe by construction: prepares a throwaway *named* volume from the base backup via a
# one-shot helper container (never touches the live cluster or its data directory), then
# starts a real throwaway postgres container against that volume with the live WAL archive
# mounted READ-ONLY. `trap cleanup EXIT` removes both the container and the volume, always.
#
#   deploy/pitr-drill.sh                                    # newest base backup, replay everything available
#   deploy/pitr-drill.sh --target-time '2026-07-15 03:00:00Z'  # replay up to (and stop at) this instant
#   deploy/pitr-drill.sh /path/to/base-*.tar.gz --target-time '...'   # a specific base backup
#
# Env: PI_BACKUP_DIR (default /var/backups/pi-audit — base backups under
#      $PI_BACKUP_DIR/basebackups, WAL archive under $PI_BACKUP_DIR/wal-archive),
#      PI_DB / PI_DB_USER (default inspector), PI_PITR_TARGET_TIME (same as --target-time).
set -euo pipefail

PI_BACKUP_DIR="${PI_BACKUP_DIR:-/var/backups/pi-audit}"
PI_DB="${PI_DB:-inspector}"
PI_DB_USER="${PI_DB_USER:-inspector}"
BASEBACKUP_DIR="$PI_BACKUP_DIR/basebackups"
WAL_ARCHIVE_DIR="$PI_BACKUP_DIR/wal-archive"
TARGET_TIME="${PI_PITR_TARGET_TIME:-}"
SCRATCH="pi-pitr-drill-$$"
PGIMAGE="postgres:16-alpine"

log() { echo "pitr-drill: $*"; }
die() { echo "pitr-drill: FAIL: $*" >&2; exit 1; }
cleanup() {
  docker rm -f "$SCRATCH" >/dev/null 2>&1 || true
  docker volume rm "${SCRATCH}-pgdata" >/dev/null 2>&1 || true
}
trap cleanup EXIT

command -v docker >/dev/null || die "docker not found on PATH"

# Args: an optional base-backup path (positional) and/or --target-time <ts>. Order-independent.
BASEBACKUP=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --target-time)
      [[ $# -ge 2 ]] || die "--target-time needs a value"
      TARGET_TIME="$2"
      shift 2
      ;;
    *)
      BASEBACKUP="$1"
      shift
      ;;
  esac
done

if [[ -z "$BASEBACKUP" ]]; then
  BASEBACKUP="$(ls -1t "$BASEBACKUP_DIR"/base-*.tar.gz 2>/dev/null | head -1 || true)"
  [[ -n "$BASEBACKUP" ]] || die "no base-*.tar.gz found in $BASEBACKUP_DIR (run basebackup-audit-db.sh first)"
fi
[[ -f "$BASEBACKUP" ]] || die "base backup not found: $BASEBACKUP"
[[ -d "$WAL_ARCHIVE_DIR" ]] || die "no WAL archive at $WAL_ARCHIVE_DIR (archiving not yet activated? see deploy/README.md)"

log "drilling $BASEBACKUP against WAL archive $WAL_ARCHIVE_DIR"
[[ -n "$TARGET_TIME" ]] && log "recovery target time: $TARGET_TIME" || log "recovery target: replay everything available"

if [[ -f "${BASEBACKUP}.sha256" ]] && command -v sha256sum >/dev/null; then
  ( cd "$(dirname "$BASEBACKUP")" && sha256sum -c "$(basename "$BASEBACKUP").sha256" ) >/dev/null \
    || die "checksum mismatch — the base backup is corrupt"
  log "checksum OK"
fi

docker volume create "${SCRATCH}-pgdata" >/dev/null

log "unpacking base backup + writing recovery config into a scratch volume"
# Helper container: root, one-shot, removed immediately after (--rm). It never runs postgres
# itself — it only unpacks the tar and writes recovery.signal / restore_command, exactly the
# Postgres-16-current mechanism (no recovery.conf). Ownership must end up as the `postgres`
# user/group baked into this same image, or the real postgres process (started as that user
# by the entrypoint below) will refuse to start against files it doesn't own.
docker run --rm \
  -v "${SCRATCH}-pgdata:/pgdata" \
  -v "$(cd "$(dirname "$BASEBACKUP")" && pwd)/$(basename "$BASEBACKUP"):/base.tar.gz:ro" \
  -e PITR_TARGET_TIME="$TARGET_TIME" \
  "$PGIMAGE" bash -c '
    set -euo pipefail
    mkdir -p /pgdata
    tar -xzf /base.tar.gz -C /pgdata
    touch /pgdata/recovery.signal
    {
      echo "restore_command = '"'"'cp /wal-archive/%f %p'"'"'"
      if [[ -n "${PITR_TARGET_TIME:-}" ]]; then
        echo "recovery_target_time = '"'"'${PITR_TARGET_TIME}'"'"'"
        echo "recovery_target_action = '"'"'promote'"'"'"
      fi
    } >> /pgdata/postgresql.auto.conf
    chown -R postgres:postgres /pgdata
    chmod 700 /pgdata
  ' || die "base-backup unpack / recovery-config step failed"

log "starting throwaway Postgres ($PGIMAGE) as '$SCRATCH' — will replay WAL then $( [[ -n "$TARGET_TIME" ]] && echo "stop at the target time" || echo "auto-promote at end of available WAL" )"
docker run -d --name "$SCRATCH" \
  -e POSTGRES_PASSWORD=drill -e POSTGRES_USER="$PI_DB_USER" -e POSTGRES_DB="$PI_DB" \
  -v "${SCRATCH}-pgdata:/var/lib/postgresql/data" \
  -v "$WAL_ARCHIVE_DIR:/wal-archive:ro" \
  "$PGIMAGE" >/dev/null
# recovery.signal being present means the entrypoint's own init dance is bypassed (it only
# runs for a fresh, empty PGDATA) — Postgres starts straight into archive recovery using the
# data we just unpacked, exactly like the real crash-recovery path a live restore would take.

# Bounded readiness wait, same "3 consecutive successful queries" pattern as restore-drill.sh
# (a transient init-time or mid-replay server must not be mistaken for a ready one) — given a
# longer ceiling since WAL replay can take longer than a plain pg_restore.
ok=0
for _ in $(seq 1 180); do
  if docker exec "$SCRATCH" psql -tAqX -U "$PI_DB_USER" -d "$PI_DB" -c 'SELECT 1' >/dev/null 2>&1; then
    ok=$((ok + 1))
    [[ "$ok" -ge 3 ]] && break
  else
    ok=0
  fi
  sleep 1
done
[[ "$ok" -ge 3 ]] || die "throwaway Postgres did not become ready (check: docker logs $SCRATCH)"

q() { docker exec "$SCRATCH" psql -tAqX -U "$PI_DB_USER" -d "$PI_DB" -c "$1"; }

# Confirm recovery actually completed (promoted out of recovery), not merely that the server
# accepts connections — hot_standby=on (PG16 default) allows read queries mid-replay too, and
# we want the drill to prove the FULL PITR path, not an in-progress one.
IN_RECOVERY="$(q 'SELECT pg_is_in_recovery();' | tr -d '[:space:]')"
[[ "$IN_RECOVERY" == "f" ]] || die "still in recovery after the readiness wait (pg_is_in_recovery=true) — WAL replay stalled or is waiting on a target that was never reached"

# Same integrity checks as restore-drill.sh:
# 1) the audit table exists and is PARTITIONED (relkind 'p').
PARTITIONED="$(q "SELECT relkind FROM pg_class WHERE relname='audit_entry';" | tr -d '[:space:]')"
[[ "$PARTITIONED" == "p" ]] || die "audit_entry missing or not partitioned after PITR replay (relkind='$PARTITIONED')"

# 2) at least one partition came back.
PARTS="$(q "SELECT count(*) FROM pg_inherits i JOIN pg_class p ON p.oid=i.inhparent WHERE p.relname='audit_entry';" | tr -d '[:space:]')"
[[ "${PARTS:-0}" -ge 1 ]] || die "audit_entry has no partitions after PITR replay"

# 3) row count (informational).
ROWS="$(q "SELECT count(*) FROM audit_entry;" | tr -d '[:space:]')"

log "PASS — PITR replay complete (promoted, not in recovery): audit_entry partitioned, ${PARTS} partition(s), ${ROWS} row(s)"
