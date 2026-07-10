#!/usr/bin/env bash
# backup-audit-db.sh — a real backup for the legally load-bearing audit store
# (IMPROVEMENT-PLAN-2026-07 P0 #4 / Q4). The BFF's Postgres holds the 400-day audit chain
# (revFADP) whose retention + legal-hold machinery (M4-closeout) guards data that, until now,
# had ZERO copies — one docker volume on one host. This takes a consistent logical dump to a
# SEPARATE location (ideally a second disk), so a lost volume or a stray `docker volume rm`
# no longer destroys the golden master.
#
# It is a LOGICAL nightly dump (`pg_dump -Fc`), so the honest RPO is the timer interval
# (24h by default), NOT the ≤5-min WAL/PITR the docs once claimed — see OPERATIONS §4.
# Continuous PITR (WAL archiving) is a follow-up; this closes the "no copy at all" gap first.
#
# Install as a nightly systemd timer (deploy/systemd/) or a cron line. Read-only against the
# live DB (pg_dump takes a consistent snapshot); safe to run while the demo serves traffic.
#
#   PI_BACKUP_DIR=/mnt/backups/pi-audit deploy/backup-audit-db.sh
#
# Env (all optional, sane demo defaults):
#   PI_PG_CONTAINER   docker container running Postgres      (default: process-inspector-demo-postgres-1)
#   PI_DB / PI_DB_USER    database + role                    (default: inspector / inspector)
#   PI_BACKUP_DIR     destination dir, ideally a 2nd disk    (default: /var/backups/pi-audit)
#   PI_BACKUP_RETENTION_DAYS  prune dumps older than N days  (default: 35)
set -euo pipefail

PI_PG_CONTAINER="${PI_PG_CONTAINER:-process-inspector-demo-postgres-1}"
PI_DB="${PI_DB:-inspector}"
PI_DB_USER="${PI_DB_USER:-inspector}"
PI_BACKUP_DIR="${PI_BACKUP_DIR:-/var/backups/pi-audit}"
PI_BACKUP_RETENTION_DAYS="${PI_BACKUP_RETENTION_DAYS:-35}"

log() { echo "backup-audit-db: $*"; }
die() { echo "backup-audit-db: ERROR: $*" >&2; exit 1; }

command -v docker >/dev/null || die "docker not found on PATH"
docker ps --format '{{.Names}}' | grep -qx "$PI_PG_CONTAINER" \
  || die "Postgres container '$PI_PG_CONTAINER' is not running (set PI_PG_CONTAINER)"

mkdir -p "$PI_BACKUP_DIR" || die "cannot create backup dir $PI_BACKUP_DIR"

# UTC, second precision, filesystem-safe. (No Date.now nondeterminism concern — this is a script.)
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$PI_BACKUP_DIR/${PI_DB}-${STAMP}.dump"
TMP="${OUT}.partial"

log "dumping '$PI_DB' from container '$PI_PG_CONTAINER' → $OUT"
# -Fc: compressed custom format (selective pg_restore, parallelizable). Stream to a .partial and
# rename only on success so a crashed dump never masquerades as a good backup.
if ! docker exec "$PI_PG_CONTAINER" pg_dump -U "$PI_DB_USER" -d "$PI_DB" -Fc > "$TMP"; then
  rm -f "$TMP"
  die "pg_dump failed — NO backup written (existing backups untouched)"
fi
mv "$TMP" "$OUT"

# A checksum so restore-drill.sh can prove integrity before trusting a dump.
if command -v sha256sum >/dev/null; then
  ( cd "$PI_BACKUP_DIR" && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256" )
fi

BYTES="$(wc -c < "$OUT")"
log "wrote ${BYTES} bytes"

# Prune old dumps (+ orphan checksums) — retention on the COPIES; the DB's own audit retention
# is a separate, DB-side concern (M4-closeout).
find "$PI_BACKUP_DIR" -maxdepth 1 -name "${PI_DB}-*.dump" -type f \
  -mtime +"$PI_BACKUP_RETENTION_DAYS" -print -delete | while read -r p; do log "pruned $(basename "$p")"; done
find "$PI_BACKUP_DIR" -maxdepth 1 -name "${PI_DB}-*.dump.sha256" -type f \
  -mtime +"$PI_BACKUP_RETENTION_DAYS" -delete || true

log "OK ($OUT)"
