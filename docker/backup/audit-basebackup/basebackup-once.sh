#!/usr/bin/env bash
# basebackup-once.sh — the containerized, Docker-native replacement for
# deploy/basebackup-audit-db.sh's `docker exec` version (issue #201-followup). Runs INSIDE
# the `audit-basebackup` compose service's crond, or standalone/on-demand via:
#
#   docker compose -f docker/docker-compose.demo.yml run --rm audit-basebackup /scripts/basebackup-once.sh
#
# Preserves basebackup-audit-db.sh's exact safety properties and reasoning — see that script's
# header for the full "why physical + why weekly + why keep only 3" rationale, unchanged here.
#
# The one deliberate behavior change: pg_basebackup connects over the NETWORK
# (PGHOST=postgres) using the replication protocol with password auth (PGUSER/PGPASSWORD set
# by the compose service), not `docker exec -h 127.0.0.1` trusting a local socket. This
# REQUIRES the demo Postgres's pg_hba.conf to grant a `replication` connection from other
# containers on the internal network — see docker/backup/postgres/pg_hba-demo.conf, wired in
# via docker-compose.demo.yml's `postgres` service `hba_file` override. Verified empirically
# against a disposable postgres:16-alpine instance (see the PR's rehearsal notes) — the
# upstream image's DEFAULT pg_hba.conf only trusts replication connections from 127.0.0.1/::1,
# so a plain network pg_basebackup fails closed (`no pg_hba.conf entry for replication
# connection`) without this override.
#
# Env (all optional beyond what the compose service already sets):
#   PI_BASEBACKUP_DIR               destination dir — the inspector-basebackups volume's mount
#                                    point inside this container (default: /basebackups)
#   PI_BASEBACKUP_RETENTION_COUNT   keep the newest N base backups (default: 3, matches
#                                    basebackup-audit-db.sh)
set -euo pipefail

PGUSER="${PGUSER:-inspector}"
BASEBACKUP_DIR="${PI_BASEBACKUP_DIR:-/basebackups}"
PI_BASEBACKUP_RETENTION_COUNT="${PI_BASEBACKUP_RETENTION_COUNT:-3}"

log() { echo "audit-basebackup: $*"; }
die() { echo "audit-basebackup: ERROR: $*" >&2; exit 1; }

command -v pg_basebackup >/dev/null || die "pg_basebackup not found on PATH"
mkdir -p "$BASEBACKUP_DIR" || die "cannot create basebackup dir $BASEBACKUP_DIR"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$BASEBACKUP_DIR/base-${STAMP}.tar.gz"
TMP="${OUT}.partial"

log "streaming a physical base backup from ${PGHOST:-<unset>} over the network -> $OUT"
# Same flags as basebackup-audit-db.sh (-D - / -Ft -z / -X none / -c fast — see that script's
# header for why each one), with an explicit -h/-U since we're no longer relying on
# docker-exec's implicit local connection.
if ! pg_basebackup -h "${PGHOST:-postgres}" -U "$PGUSER" -D - -Ft -z -X none -c fast > "$TMP"; then
  rm -f "$TMP"
  die "pg_basebackup failed — NO base backup written (existing base backups untouched)"
fi
mv "$TMP" "$OUT"

if command -v sha256sum >/dev/null; then
  ( cd "$BASEBACKUP_DIR" && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256" )
fi

BYTES="$(wc -c < "$OUT")"
log "wrote ${BYTES} bytes"

# Retention: keep only the newest N (count-based, not age-based — see basebackup-audit-db.sh's
# header). Sorted newest-first by the filename's own UTC timestamp (lexicographic ==
# chronological here) — identical logic to that script.
mapfile -t ALL < <(ls -1 "$BASEBACKUP_DIR"/base-*.tar.gz 2>/dev/null | sort -r)
if [[ "${#ALL[@]}" -gt "$PI_BASEBACKUP_RETENTION_COUNT" ]]; then
  for old in "${ALL[@]:$PI_BASEBACKUP_RETENTION_COUNT}"; do
    rm -f "$old" "${old}.sha256"
    log "pruned $(basename "$old")"
  done
fi

log "OK ($OUT)"
