#!/usr/bin/env bash
# backup-once.sh — the containerized, Docker-native replacement for deploy/backup-audit-db.sh's
# `docker exec` version (issue #201-followup). Runs INSIDE the `audit-backup` compose service's
# crond (docker/backup/audit-backup/entrypoint.sh), nightly, OR standalone/on-demand via:
#
#   docker compose -f docker/docker-compose.demo.yml run --rm audit-backup /scripts/backup-once.sh
#
# Preserves backup-audit-db.sh's exact safety properties — see that script's own header for
# the full rationale, unchanged here:
#   - a consistent pg_dump -Fc snapshot (safe to run while the demo serves traffic)
#   - .partial -> rename crash-safety (a crashed dump never masquerades as a good backup)
#   - a sha256sum checksum sidecar (restore-drill.sh proves integrity before trusting a dump)
#   - retention pruning, same default (35 days)
#
# The one deliberate behavior change: pg_dump connects over the NETWORK (PGHOST=postgres,
# libpq env vars set by docker-compose.demo.yml's `audit-backup` service — PGUSER/PGPASSWORD/
# PGDATABASE), not `docker exec`-ing into the Postgres container. pg_dump reads all of
# PGHOST/PGUSER/PGPASSWORD/PGDATABASE natively; no explicit -h/-U flags needed here.
#
# Env (all optional beyond what the compose service already sets):
#   PI_DUMP_DIR                destination dir — the inspector-logical-dumps volume's mount
#                               point inside this container (default: /dumps)
#   PI_BACKUP_RETENTION_DAYS   prune dumps older than N days (default: 35, matches
#                               backup-audit-db.sh)
set -euo pipefail

PGDATABASE="${PGDATABASE:-inspector}"
OUT_DIR="${PI_DUMP_DIR:-/dumps}"
PI_BACKUP_RETENTION_DAYS="${PI_BACKUP_RETENTION_DAYS:-35}"

log() { echo "audit-backup: $*"; }
die() { echo "audit-backup: ERROR: $*" >&2; exit 1; }

command -v pg_dump >/dev/null || die "pg_dump not found on PATH"
mkdir -p "$OUT_DIR" || die "cannot create dump dir $OUT_DIR"

# UTC, second precision, filesystem-safe — same stamp format as backup-audit-db.sh so dumps
# from either mechanism sort/compare identically.
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$OUT_DIR/${PGDATABASE}-${STAMP}.dump"
TMP="${OUT}.partial"

log "dumping '$PGDATABASE' from ${PGHOST:-<unset>} over the network -> $OUT"
if ! pg_dump -Fc > "$TMP"; then
  rm -f "$TMP"
  die "pg_dump failed — NO backup written (existing backups untouched)"
fi
mv "$TMP" "$OUT"

# A checksum so restore-drill.sh (updated to read this volume) can prove integrity before
# trusting a dump.
if command -v sha256sum >/dev/null; then
  ( cd "$OUT_DIR" && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256" )
fi

BYTES="$(wc -c < "$OUT")"
log "wrote ${BYTES} bytes"

# Prune old dumps (+ orphan checksums) — retention on the COPIES; the DB's own audit retention
# is a separate, DB-side concern (M4-closeout). Identical logic to backup-audit-db.sh.
find "$OUT_DIR" -maxdepth 1 -name "${PGDATABASE}-*.dump" -type f \
  -mtime +"$PI_BACKUP_RETENTION_DAYS" -print -delete | while read -r p; do log "pruned $(basename "$p")"; done
find "$OUT_DIR" -maxdepth 1 -name "${PGDATABASE}-*.dump.sha256" -type f \
  -mtime +"$PI_BACKUP_RETENTION_DAYS" -delete || true

log "OK ($OUT)"
