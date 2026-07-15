#!/usr/bin/env bash
# pitr-drill.sh — makes point-in-time recovery an EXECUTABLE proof, mirroring
# restore-drill.sh's safe-by-construction pattern exactly: a THROWAWAY Postgres, never the
# live DB, torn down on exit. Where restore-drill.sh proves the nightly LOGICAL dump restores,
# this proves the PHYSICAL path (issue #201 / #201-followup): an `audit-basebackup` base
# backup, replayed forward through the continuously captured WAL segments (the `wal-receiver`
# compose service, issue #201-followup — or, on a host still running the superseded
# archive_command mechanism, the archive dir it wrote to), up to either "as much as is
# available" (default) or an explicit --target-time.
#
# Recovery mechanism: Postgres 16's CURRENT approach — a `recovery.signal` marker file plus
# `restore_command` (+ optional `recovery_target_time`/`recovery_target_action`) appended to
# `postgresql.auto.conf` inside PGDATA. NOT the pre-12 `recovery.conf` file, which Postgres
# removed entirely (a server with a `recovery.conf` present today just refuses to start).
#
# Safe by construction: prepares a throwaway *named* volume from the base backup via a
# one-shot helper container (never touches the live cluster or its data directory), then
# starts a real throwaway postgres container against that volume with the WAL archive mounted
# READ-ONLY. `trap cleanup EXIT` removes the container and the scratch pgdata volume, always.
#
# STORAGE SOURCE (issue #201-followup — Docker-native backups):
#   Base backups now live in the named Docker volume `inspector-basebackups` (written by the
#   `audit-basebackup` compose service) and WAL segments in `inspector-wal-archive` (written
#   continuously by the `wal-receiver` compose service) — no host bind-mounts. Both are
#   mounted DIRECTLY by name into the helper/scratch containers below; nothing is copied to
#   the host first.
#
#   Legacy/manual use: pass a HOST PATH as the first positional argument to use an
#   old-style `basebackup-audit-db.sh` base backup + a host-directory WAL archive instead
#   (set PI_WAL_ARCHIVE_HOST_DIR for the latter) — for anyone still on the superseded
#   deploy/systemd/pi-audit-basebackup.* mechanism.
#
#   deploy/pitr-drill.sh                                          # newest base backup in the volume, replay everything available
#   deploy/pitr-drill.sh --target-time '2026-07-15 03:00:00Z'      # replay up to (and stop at) this instant
#   deploy/pitr-drill.sh base-20260715T040000Z.tar.gz               # a specific base-backup FILENAME within the volume
#   deploy/pitr-drill.sh /path/to/base-*.tar.gz --target-time '...' # a specific HOST PATH (legacy mode)
#
# Env: PI_BASEBACKUPS_VOLUME (default inspector-basebackups), PI_WAL_ARCHIVE_VOLUME (default
#      inspector-wal-archive), PI_DB / PI_DB_USER (default inspector), PI_PITR_TARGET_TIME
#      (same as --target-time).
set -euo pipefail

PI_DB="${PI_DB:-inspector}"
PI_DB_USER="${PI_DB_USER:-inspector}"
PI_BASEBACKUPS_VOLUME="${PI_BASEBACKUPS_VOLUME:-inspector-basebackups}"
PI_WAL_ARCHIVE_VOLUME="${PI_WAL_ARCHIVE_VOLUME:-inspector-wal-archive}"
PI_WAL_ARCHIVE_HOST_DIR="${PI_WAL_ARCHIVE_HOST_DIR:-}"
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

# Args: an optional base-backup reference (positional — host path OR in-volume filename)
# and/or --target-time <ts>. Order-independent.
BASEBACKUP_ARG=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --target-time)
      [[ $# -ge 2 ]] || die "--target-time needs a value"
      TARGET_TIME="$2"
      shift 2
      ;;
    *)
      BASEBACKUP_ARG="$1"
      shift
      ;;
  esac
done

USE_VOLUME=1
BASEBACKUP_HOST_PATH=""
BASEBACKUP_FILENAME=""
if [[ -n "$BASEBACKUP_ARG" && -f "$BASEBACKUP_ARG" ]]; then
  USE_VOLUME=0
  BASEBACKUP_HOST_PATH="$BASEBACKUP_ARG"
elif [[ -n "$BASEBACKUP_ARG" ]]; then
  BASEBACKUP_FILENAME="$BASEBACKUP_ARG"
fi

[[ -n "$TARGET_TIME" ]] && log "recovery target time: $TARGET_TIME" || log "recovery target: replay everything available"

docker volume create "${SCRATCH}-pgdata" >/dev/null

if [[ "$USE_VOLUME" == "1" ]]; then
  docker volume inspect "$PI_BASEBACKUPS_VOLUME" >/dev/null 2>&1 \
    || die "no Docker volume '$PI_BASEBACKUPS_VOLUME' (run 'docker compose ... run --rm audit-basebackup /scripts/basebackup-once.sh' first, or pass a host path)"
  if [[ -z "$PI_WAL_ARCHIVE_HOST_DIR" ]]; then
    docker volume inspect "$PI_WAL_ARCHIVE_VOLUME" >/dev/null 2>&1 \
      || die "no Docker volume '$PI_WAL_ARCHIVE_VOLUME' (wal-receiver hasn't run yet? or set PI_WAL_ARCHIVE_HOST_DIR for the legacy archive_command mechanism)"
  fi

  log "unpacking base backup (from volume '$PI_BASEBACKUPS_VOLUME') + writing recovery config into a scratch volume"
  # Helper container: root, one-shot, removed immediately after (--rm). Mounts the
  # basebackups volume READ-ONLY, picks the target file (newest base-*.tar.gz, or the exact
  # BASEBACKUP_FILENAME if given), verifies its checksum if present, then unpacks it and
  # writes recovery.signal / restore_command — the Postgres-16-current mechanism (no
  # recovery.conf). Ownership must end up as the `postgres` user/group baked into this same
  # image, or the real postgres process (started as that user by the entrypoint below) will
  # refuse to start against files it doesn't own.
  SELECTED="$(docker run --rm \
    -v "${SCRATCH}-pgdata:/pgdata" \
    -v "${PI_BASEBACKUPS_VOLUME}:/basebackups:ro" \
    -e PITR_TARGET_TIME="$TARGET_TIME" \
    -e BASEBACKUP_FILENAME="$BASEBACKUP_FILENAME" \
    -e WAL_RESTORE_CMD="cp /wal-archive/%f %p" \
    "$PGIMAGE" bash -c '
      set -euo pipefail
      if [[ -n "${BASEBACKUP_FILENAME:-}" ]]; then
        f="/basebackups/${BASEBACKUP_FILENAME}"
      else
        f="$(ls -1t /basebackups/base-*.tar.gz 2>/dev/null | head -1)"
      fi
      [[ -n "$f" && -f "$f" ]] || { echo "NOFILE" >&2; exit 1; }
      if [[ -f "${f}.sha256" ]]; then
        ( cd /basebackups && sha256sum -c "$(basename "${f}.sha256")" ) >&2 \
          || { echo "BADSUM" >&2; exit 1; }
      fi
      mkdir -p /pgdata
      tar -xzf "$f" -C /pgdata
      touch /pgdata/recovery.signal
      {
        echo "restore_command = '"'"'${WAL_RESTORE_CMD}'"'"'"
        if [[ -n "${PITR_TARGET_TIME:-}" ]]; then
          echo "recovery_target_time = '"'"'${PITR_TARGET_TIME}'"'"'"
          echo "recovery_target_action = '"'"'promote'"'"'"
        fi
      } >> /pgdata/postgresql.auto.conf
      chown -R postgres:postgres /pgdata
      chmod 700 /pgdata
      basename "$f"
    ')" || die "base-backup unpack / recovery-config step failed (no matching file, or checksum mismatch)"
  log "selected $SELECTED (checksum OK if a sidecar was present)"
else
  BASEBACKUP="$BASEBACKUP_HOST_PATH"
  [[ -f "$BASEBACKUP" ]] || die "base backup not found: $BASEBACKUP"
  [[ -n "$PI_WAL_ARCHIVE_HOST_DIR" && -d "$PI_WAL_ARCHIVE_HOST_DIR" ]] \
    || die "legacy host-path mode needs PI_WAL_ARCHIVE_HOST_DIR pointing at an existing WAL archive dir"
  log "drilling $BASEBACKUP against WAL archive $PI_WAL_ARCHIVE_HOST_DIR (legacy host-path mode)"

  if [[ -f "${BASEBACKUP}.sha256" ]] && command -v sha256sum >/dev/null; then
    ( cd "$(dirname "$BASEBACKUP")" && sha256sum -c "$(basename "$BASEBACKUP").sha256" ) >/dev/null \
      || die "checksum mismatch — the base backup is corrupt"
    log "checksum OK"
  fi

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
fi

log "starting throwaway Postgres ($PGIMAGE) as '$SCRATCH' — will replay WAL then $( [[ -n "$TARGET_TIME" ]] && echo "stop at the target time" || echo "auto-promote at end of available WAL" )"
if [[ "$USE_VOLUME" == "1" ]]; then
  docker run -d --name "$SCRATCH" \
    -e POSTGRES_PASSWORD=drill -e POSTGRES_USER="$PI_DB_USER" -e POSTGRES_DB="$PI_DB" \
    -v "${SCRATCH}-pgdata:/var/lib/postgresql/data" \
    -v "${PI_WAL_ARCHIVE_VOLUME}:/wal-archive:ro" \
    "$PGIMAGE" >/dev/null
else
  docker run -d --name "$SCRATCH" \
    -e POSTGRES_PASSWORD=drill -e POSTGRES_USER="$PI_DB_USER" -e POSTGRES_DB="$PI_DB" \
    -v "${SCRATCH}-pgdata:/var/lib/postgresql/data" \
    -v "$PI_WAL_ARCHIVE_HOST_DIR:/wal-archive:ro" \
    "$PGIMAGE" >/dev/null
fi
# recovery.signal being present means the entrypoint's own init dance is bypassed (it only
# runs for a fresh, empty PGDATA) — Postgres starts straight into archive recovery using the
# data we just unpacked, exactly like the real crash-recovery path a live restore would take.
#
# NOTE: `wal-receiver` (issue #201-followup) writes .partial-suffixed in-progress segments
# alongside completed ones in the SAME directory (pg_receivewal's own convention — unlike
# archive_command's atomic per-segment copy, there is no separate staging area). This is safe
# for restore_command's `cp /wal-archive/%f %p`: %f is always a COMPLETE segment's exact
# filename (recovery never asks for a `*.partial` name), so the in-progress file is simply
# never matched/copied — it becomes eligible once pg_receivewal itself renames it away from
# `.partial` on completion.

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
