#!/usr/bin/env bash
# entrypoint.sh — continuous WAL capture via `pg_receivewal`, the Docker-native replacement
# for docker-compose.demo.yml's `postgres.command` archive_mode/archive_command pair (issue
# #201-followup). Streams the replication protocol against `postgres:5432` over the network
# and writes received segments straight into the inspector-wal-archive NAMED VOLUME — no host
# bind-mount, no uid-70 host-side chown.
#
# Why a permanent replication SLOT (not just "resume from local files"): pg_receivewal on its
# own already resumes correctly from whatever is on disk when restarted (it re-requests from
# the last complete local segment) — but WITHOUT a slot, Postgres is free to recycle/remove WAL
# segments the receiver hasn't fetched yet if it stays down long enough, which would leave a
# gap no restart can heal. A permanent physical replication slot tells Postgres "retain WAL
# until pg_receivewal confirms it, no matter how long that takes" — the property that makes it
# actually safe to run this as a long-lived, restartable container service rather than merely
# "usually fine". Slot creation is idempotent (--if-not-exists) so container restarts never
# error on "slot already exists".
#
# --synchronous is deliberately OFF (the default) — status updates every --status-interval
# (default 10s) rather than an fsync-per-record, an acceptable trade for this demo-scale RPO
# (already 24h via the logical dump; this is a strict improvement, not the sole line of
# defense).
#
# DECOMMISSIONING: a permanent slot means Postgres retains WAL FOREVER if this service is
# ever removed without dropping the slot first — an orphaned slot silently grows the data
# volume's disk usage without bound. If `wal-receiver` is ever permanently removed, first run
# `docker exec process-inspector-demo-postgres-1 psql -U inspector -d inspector -c
# "SELECT pg_drop_replication_slot('wal_receiver');"` (adjust the slot name to $PI_WAL_SLOT if
# overridden) — see docs/RUNBOOK.md's Docker-native-backups section.
#
# REQUIRES: wal_level=replica (already the compose postgres service's pinned setting) and the
# connecting role to carry the REPLICATION privilege — the bootstrap POSTGRES_USER-created
# role has it implicitly per Postgres's own docs, confirmed empirically in this PR's
# rehearsal. ALSO requires docker/backup/postgres/pg_hba-demo.conf's `host replication ...`
# entry (see that file's header) — the upstream postgres:16-alpine image's default
# pg_hba.conf only trusts replication connections from 127.0.0.1/::1, so this fails closed
# without the override.
set -euo pipefail

: "${PGHOST:=postgres}"
: "${PGUSER:=inspector}"
PI_WAL_SLOT="${PI_WAL_SLOT:-wal_receiver}"
DEST="${PI_WAL_ARCHIVE_DIR:-/wal-archive}"

log() { echo "wal-receiver: $*"; }

mkdir -p "$DEST"

# Run pg_receivewal as the `postgres` user (uid 70 in this image), not root — this container
# starts as root (so it CAN chown a freshly created, root-owned named-volume mountpoint into
# existence below), but the actual streaming process must run as uid 70. This matters because
# pg_receivewal writes each WAL segment with restrictive 0600 permissions (hardcoded, matching
# how the server itself writes WAL) — a segment written as root is then UNREADABLE to the
# `postgres` user a recovery `restore_command` runs as inside pitr-drill.sh's throwaway
# container (verified empirically in this PR's rehearsal: `cp: can't open
# '.../000000010000000000000002': Permission denied` when this ran as root). Running as the
# SAME uid (70) that every postgres:16-alpine-based reader also uses internally closes that
# gap. `gosu` (baked into this image, confirmed present) drops privileges cleanly instead of a
# raw `su`. The chown is safe to repeat on every container start (idempotent; re-chowning
# already-70-owned files is a cheap no-op) — needed because a freshly created named volume's
# mountpoint is owned by root until something chowns it.
chown -R postgres:postgres "$DEST"

log "ensuring physical replication slot '$PI_WAL_SLOT' exists (idempotent)"
# A dedicated one-shot invocation just to create the slot — pg_receivewal refuses to combine
# --create-slot with an open-ended streaming run in the way we want (it creates-then-exits),
# so this is deliberately a separate call before the real (looping) one below.
if ! gosu postgres pg_receivewal -h "$PGHOST" -U "$PGUSER" -D "$DEST" \
      --create-slot --slot="$PI_WAL_SLOT" --if-not-exists; then
  log "WARNING: slot creation reported a failure (may be transient — the streaming run below will surface a real connectivity problem loudly if so)"
fi

log "starting continuous WAL streaming into $DEST (slot=$PI_WAL_SLOT, host=$PGHOST, as uid $(id -u postgres))"
# -n/--no-loop is NOT set, so pg_receivewal itself retries on a dropped connection without
# exiting; the outer `exec` (PID 1) still relies on the compose service's `restart:
# unless-stopped` for anything that DOES cause it to exit (e.g. the slot being dropped out
# from under it) — belt and suspenders, both layers were exercised in this PR's kill/restart
# rehearsal.
exec gosu postgres pg_receivewal -h "$PGHOST" -U "$PGUSER" -D "$DEST" --slot="$PI_WAL_SLOT" --verbose
