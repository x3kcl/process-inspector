#!/usr/bin/env bash
# entrypoint.sh — the audit-backup service's default (cron-daemon) mode. Installs
# docker/backup/audit-backup/crontab and runs busybox crond in the foreground so the
# container's own logs (`docker logs`) carry both crond's own log lines and — via the
# /proc/1/fd redirect in the crontab file — each backup-once.sh run's output.
#
# For a one-shot / on-demand run (not waiting for the next cron tick), OVERRIDE the command
# entirely instead of going through this entrypoint's cron branch:
#   docker compose -f docker/docker-compose.demo.yml run --rm audit-backup /scripts/backup-once.sh
#
# (docker-compose.demo.yml points this service's `entrypoint:` straight at this file, with no
# args — `docker compose run --rm audit-backup <cmd>` replaces the CONTAINER COMMAND, but
# compose's entrypoint is still prepended unless the run also passes --entrypoint. The
# audit-backup service's compose definition sets `entrypoint: ["/scripts/entrypoint.sh"]` with
# an empty default `command:`, and this script execs straight into crond when called with no
# args — so a plain `run --rm audit-backup /scripts/backup-once.sh` would actually invoke
# `entrypoint.sh /scripts/backup-once.sh`. Handle that: any argument means "run this instead
# of the cron daemon".
set -euo pipefail

if [[ $# -gt 0 ]]; then
  echo "audit-backup: one-shot mode: exec $*"
  exec "$@"
fi

CRONTAB_FILE="/scripts/crontab"
CRON_SPOOL="/var/spool/cron/crontabs"

mkdir -p "$CRON_SPOOL"
cp "$CRONTAB_FILE" "$CRON_SPOOL/root"

echo "audit-backup: installed crontab, starting crond in foreground"
echo "audit-backup: schedule: $(grep -v '^#' "$CRONTAB_FILE" | grep -v '^[[:space:]]*$')"
exec crond -f -d 8
