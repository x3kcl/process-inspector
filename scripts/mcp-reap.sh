#!/usr/bin/env bash
# mcp-reap.sh — find (and optionally stop) orphaned MCP containers on the dev host (#616).
#
# .mcp.json starts three servers as `docker run -i --rm ...` children of a Claude session.
# When a session dies without closing stdin cleanly the container keeps running (browser,
# DB connections and all). This script lists those containers and marks the orphans.
#
# Scope (hard allowlist — nothing else on the shared box is ever listed or touched):
#   ghcr.io/github/github-mcp-server · mcr.microsoft.com/playwright/mcp · crystaldba/postgres-mcp
#
# Orphan test: a running container of one of those images is a LIVE session's container
# only if a live `docker run` client process for the SAME image started within WINDOW
# seconds of the container's creation time. Each client is matched to at most one
# container (nearest first), so two sessions that start the same image in the same second
# are still told apart by count. No such client => ORPHAN.
#
# Usage:
#   scripts/mcp-reap.sh            # dry run (default): table of container, image, age, verdict
#   scripts/mcp-reap.sh --apply    # `docker stop` the ORPHAN rows only (--rm removes them)
#   MCP_REAP_WINDOW=10 scripts/mcp-reap.sh   # start-time tolerance in seconds (default 10)
#
# Never prints process command lines (the github server's client carries a token in argv).
# Run it on the host that owns the containers, as a user that may talk to the docker socket.
set -euo pipefail

APPLY=0
case "${1:-}" in
  "") ;;
  --apply) APPLY=1 ;;
  -h|--help) sed -n '2,23p' "$0"; exit 0 ;;
  *) echo "usage: $0 [--apply]" >&2; exit 2 ;;
esac
WINDOW="${MCP_REAP_WINDOW:-10}"
IMAGES=(ghcr.io/github/github-mcp-server mcr.microsoft.com/playwright/mcp crystaldba/postgres-mcp)

# strip a trailing :tag (only when the colon is in the last path segment, so registry ports survive)
base() { local i="$1"; [[ "${i##*/}" == *:* ]] && i="${i%:*}"; printf '%s' "$i"; }
in_scope() { local b; b="$(base "$1")"; for i in "${IMAGES[@]}"; do [ "$b" = "$i" ] && return 0; done; return 1; }

# live clients: "image epoch" lines, one per `docker ... run ... <image>` process
CLIENTS=()
while read -r _pid _dow mon day tm year args; do
  read -r -a tok <<<"$args"
  [ "${#tok[@]}" -gt 1 ] || continue
  [ "$(basename "${tok[0]}")" = docker ] || continue
  is_run=0; img=""
  for t in "${tok[@]:1}"; do
    [ "$t" = run ] && is_run=1
    in_scope "$t" && { img="$(base "$t")"; break; }
  done
  [ "$is_run" = 1 ] && [ -n "$img" ] || continue
  CLIENTS+=("$img $(date -d "$mon $day $tm $year" +%s)")
done < <(ps -eo pid=,lstart=,args=)

USED=()  # indexes into CLIENTS already matched
now="$(date +%s)"
rows=(); orphans=()
# oldest first so the nearest-match assignment is stable
while read -r id created image; do
  b="$(base "$image")"; c="$(date -d "$created" +%s)"
  best=-1; bestd=$((WINDOW + 1))
  for idx in "${!CLIENTS[@]}"; do
    [[ " ${USED[*]:-} " == *" $idx "* ]] && continue
    read -r cimg cep <<<"${CLIENTS[$idx]}"
    [ "$cimg" = "$b" ] || continue
    d=$((cep > c ? cep - c : c - cep))
    [ "$d" -lt "$bestd" ] && { best=$idx; bestd=$d; }
  done
  age=$((now - c)); age_s="$((age / 86400))d$(((age % 86400) / 3600))h$(((age % 3600) / 60))m"
  if [ "$best" -ge 0 ]; then USED+=("$best"); v=live
  else v=ORPHAN; orphans+=("$id"); fi
  rows+=("$(printf '%-14s %-38s %-9s %s' "$id" "$b" "$age_s" "$v")")
done < <(docker ps --format '{{.ID}} {{.Image}}' | while read -r id image; do
           in_scope "$image" || continue
           echo "$id $(docker inspect -f '{{.Created}}' "$id") $image"
         done | sort -k2)

printf '%-14s %-38s %-9s %s\n' CONTAINER IMAGE AGE VERDICT
[ "${#rows[@]}" -gt 0 ] && printf '%s\n' "${rows[@]}"
echo "-- ${#rows[@]} MCP container(s), ${#orphans[@]} orphan(s), ${#CLIENTS[@]} live client(s) (window ${WINDOW}s)"

if [ "${#orphans[@]}" -eq 0 ]; then exit 0; fi
if [ "$APPLY" = 1 ]; then
  docker stop "${orphans[@]}" >/dev/null
  echo "stopped ${#orphans[@]} orphan(s)"
else
  echo "dry run: re-run with --apply to stop the ${#orphans[@]} ORPHAN row(s) above"
fi
