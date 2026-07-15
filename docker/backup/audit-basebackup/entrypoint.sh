#!/usr/bin/env bash
# entrypoint.sh — the audit-basebackup service's default (cron-daemon) mode. Mirrors
# docker/backup/audit-backup/entrypoint.sh exactly — see that file's header for the full
# one-shot-override explanation.
#
#   docker compose -f docker/docker-compose.demo.yml run --rm audit-basebackup /scripts/basebackup-once.sh
set -euo pipefail

if [[ $# -gt 0 ]]; then
  echo "audit-basebackup: one-shot mode: exec $*"
  exec "$@"
fi

CRONTAB_FILE="/scripts/crontab"
CRON_SPOOL="/var/spool/cron/crontabs"

mkdir -p "$CRON_SPOOL"
cp "$CRONTAB_FILE" "$CRON_SPOOL/root"

echo "audit-basebackup: installed crontab, starting crond in foreground"
echo "audit-basebackup: schedule: $(grep -v '^#' "$CRONTAB_FILE" | grep -v '^[[:space:]]*$')"
exec crond -f -d 8
