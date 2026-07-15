#!/usr/bin/env bash
# basebackup-audit-db.sh — the PHYSICAL half of continuous PITR for the audit store
# (issue #201). backup-audit-db.sh's nightly `pg_dump` is a LOGICAL dump: complete on its
# own, but only as fresh as the last run (24h RPO). WAL archiving (docker-compose.demo.yml's
# `postgres.command`, once activated — see deploy/README.md) closes that gap CONTINUOUSLY,
# but WAL segments replay FORWARD from a physical base, not from a logical dump — the two
# mechanisms are incompatible. This produces that physical base: a `pg_basebackup` streamed
# straight to a compressed tar, the anchor `pitr-drill.sh` replays WAL on top of.
#
# Like backup-audit-db.sh: read-only against the live DB (pg_basebackup takes an internal
# checkpoint + streams, no exclusive locks that block writers), safe to run while the demo
# serves traffic. Crash-safety is the same .partial→rename pattern.
#
# Retention is DELIBERATELY SHORTER than the logical dump's (35 days nightly): a physical
# base backup is the FULL data directory (gigabytes, not the compact logical-dump format),
# and every backup after the FIRST is redundant with the continuous WAL archive for recovery
# purposes — WAL replay from ANY older-but-still-archived base backup reaches the same point,
# just with a longer replay. Keeping only the last few trades a bit of drill replay time for
# a lot of disk: default keeps the last 3 (PI_BASEBACKUP_RETENTION_COUNT), paired with a
# WEEKLY timer (deploy/systemd/pi-audit-basebackup.timer) — roughly 3 weeks of "start fresh
# from here" anchors, well inside the WAL archive's own retention (pruned independently, see
# deploy/README.md), never a single point of failure on one backup.
#
#   PI_BACKUP_DIR=/mnt/backups/pi-audit deploy/basebackup-audit-db.sh
#
# Env (all optional, sane demo defaults; names mirror backup-audit-db.sh where they overlap):
#   PI_PG_CONTAINER   docker container running Postgres      (default: process-inspector-demo-postgres-1)
#   PI_DB_USER            role to connect as (must have REPLICATION; the POSTGRES_USER-created
#                          superuser has it implicitly) (default: inspector)
#   PI_BACKUP_DIR     base dir, ideally a 2nd disk (shared with backup-audit-db.sh)
#                                                          (default: /var/backups/pi-audit)
#   PI_BASEBACKUP_RETENTION_COUNT  keep the newest N base backups (default: 3)
set -euo pipefail

PI_PG_CONTAINER="${PI_PG_CONTAINER:-process-inspector-demo-postgres-1}"
PI_DB_USER="${PI_DB_USER:-inspector}"
PI_BACKUP_DIR="${PI_BACKUP_DIR:-/var/backups/pi-audit}"
PI_BASEBACKUP_RETENTION_COUNT="${PI_BASEBACKUP_RETENTION_COUNT:-3}"
BASEBACKUP_DIR="$PI_BACKUP_DIR/basebackups"

log() { echo "basebackup-audit-db: $*"; }
die() { echo "basebackup-audit-db: ERROR: $*" >&2; exit 1; }

command -v docker >/dev/null || die "docker not found on PATH"
docker ps --format '{{.Names}}' | grep -qx "$PI_PG_CONTAINER" \
  || die "Postgres container '$PI_PG_CONTAINER' is not running (set PI_PG_CONTAINER)"

mkdir -p "$BASEBACKUP_DIR" || die "cannot create basebackup dir $BASEBACKUP_DIR"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$BASEBACKUP_DIR/base-${STAMP}.tar.gz"
TMP="${OUT}.partial"

log "streaming a physical base backup from '$PI_PG_CONTAINER' → $OUT"
# -D -           : write the single-tablespace tar to stdout (this demo has no extra
#                  tablespaces — a second tablespace would need a directory target instead).
# -Ft -z         : tar format, gzip-compressed — one self-contained file, easy to move/retain.
# -X none        : do NOT bundle WAL in the base backup itself (bundling is also incompatible
#                  with -D - for anything beyond the single main tar). This base backup is
#                  only ever restorable TOGETHER WITH the continuously archived WAL segments
#                  (archive_command target, see docker-compose.demo.yml) — exactly the PITR
#                  pairing this issue ships, and exactly what pitr-drill.sh proves end to end.
# -c fast        : request an immediate checkpoint rather than waiting for the next scheduled
#                  one — a slightly bigger I/O spike, traded for a base backup that starts
#                  promptly instead of stalling for up to checkpoint_timeout.
# -h 127.0.0.1   : the image's default pg_hba.conf trusts local/127.0.0.1 for the
#                  `replication` pseudo-database (confirmed against a running instance); no
#                  password needed, and this stays true whether or not POSTGRES_PASSWORD was
#                  customized.
if ! docker exec "$PI_PG_CONTAINER" \
    pg_basebackup -D - -Ft -z -X none -c fast -h 127.0.0.1 -U "$PI_DB_USER" > "$TMP"; then
  rm -f "$TMP"
  die "pg_basebackup failed — NO base backup written (existing base backups untouched)"
fi
mv "$TMP" "$OUT"

if command -v sha256sum >/dev/null; then
  ( cd "$BASEBACKUP_DIR" && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256" )
fi

BYTES="$(wc -c < "$OUT")"
log "wrote ${BYTES} bytes"

# Retention: keep only the newest N (count-based, not age-based — see header). Sorted
# newest-first by the filename's own UTC timestamp (lexicographic == chronological here).
mapfile -t ALL < <(ls -1 "$BASEBACKUP_DIR"/base-*.tar.gz 2>/dev/null | sort -r)
if [[ "${#ALL[@]}" -gt "$PI_BASEBACKUP_RETENTION_COUNT" ]]; then
  for old in "${ALL[@]:$PI_BASEBACKUP_RETENTION_COUNT}"; do
    rm -f "$old" "${old}.sha256"
    log "pruned $(basename "$old")"
  done
fi

log "OK ($OUT)"
