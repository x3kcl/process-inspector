#!/usr/bin/env bash
# ── Feed + deploy the CI web dashboard (deploy/ci-dashboard) ──────────────────────────
# Runs on the PROBE box (hp04 — the box that holds GITHUB_PERSONAL_ACCESS_TOKEN). Renders
# scripts/ci-status.sh --json and rsyncs it to the dashboard's site dir on hp02, where the
# pi-ci-dashboard container serves it (http://<hp02>:8091).
#
#   bash scripts/ci-dashboard-push.sh                 # one refresh push
#   bash scripts/ci-dashboard-push.sh --deploy        # sync compose+page to hp02 AND (re)start
#                                                     #   the container, then push once
#   bash scripts/ci-dashboard-push.sh --install-cron  # refresh every minute (idempotent,
#                                                     #   marker pi-ci-dashboard)
#   bash scripts/ci-dashboard-push.sh --uninstall-cron
#   bash scripts/ci-dashboard-push.sh --selftest         # render-only; can it run at all?
#
# The cron writes its last cycle's output to /tmp/pi-ci-dashboard-push.log (overwritten
# each run, so it never grows) — read that first when the board goes STALE.
#
# The cron runs from wherever you install it, so that checkout is what the board actually
# executes: after changing anything under scripts/ or deploy/ci-dashboard/, refresh it
# (`git -C ~/workspace/pi-wt-ci-dash pull --ff-only`) and re-run --deploy. --install-cron
# refuses to run from .claude/worktrees/ so the cron can never be pinned to scratch space.
#
# Why the split: only hp04 can mint the API calls (the PAT is env-ref only and lives
# there), and only hp02 is the always-on box the operator's browser points at. Shipping
# RENDERED JSON rather than the token keeps the secret on exactly one box — the dashboard
# host never needs, sees, or stores a credential.
#
# The JSON carries absolute epochs; the page computes ages client-side, so if this pusher
# dies the dashboard SHOWS staleness rather than a frozen "live" view.
#
# Secrets: GITHUB_PERSONAL_ACCESS_TOKEN is consumed by ci-status.sh and never echoed,
# logged, or transmitted.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 2

# cron-proof PATH: cron's minimal PATH lacks the tools this needs on some boxes.
export PATH="$HOME/.local/bin:/usr/local/bin:$PATH"

# cron-proof TOKEN. cron runs a non-interactive, non-login shell, and this box keeps the
# PAT as an `export` in ~/.bashrc — which early-returns for non-interactive shells
# (`case $- in *i*) ;; *) return;; esac`, the Debian default). So under cron the variable is
# simply absent, ci-status.sh bails, and every refresh fails: the board ages into STALE and
# never comes back. Observed exactly that within minutes of installing the first cron.
# Pull ONLY that one export line, rather than sourcing the whole rc (which would drag in the
# interactive setup) and rather than copying the secret into a second file — the env-ref
# iron rule wants exactly one source of truth for it. Never echoed, here or anywhere.
if [ -z "${GITHUB_PERSONAL_ACCESS_TOKEN:-}" ] && [ -r "$HOME/.bashrc" ]; then
  eval "$(grep -hE '^[[:space:]]*export[[:space:]]+GITHUB_PERSONAL_ACCESS_TOKEN=' "$HOME/.bashrc" | tail -1)"
fi

DASH_HOST="${PI_DASH_HOST:-flapci@172.16.62.253}"
DASH_DIR="${PI_DASH_DIR:-~/pi-ci-dashboard}"
DASH_PORT="${DASH_PORT:-8091}"
LOCKFILE="/tmp/pi-ci-dashboard-push.lock"
CRON_LOG="/tmp/pi-ci-dashboard-push.log"

push_json() {
  # Serialize crons: a slow probe cycle (a dozen GitHub API calls) must queue-DROP, not
  # pile up a minute at a time until the box is full of stacked curls.
  exec 8>"$LOCKFILE"
  flock -n 8 || { echo "── push already running — skipped"; exit 0; }
  local tmp; tmp="$(mktemp)"
  if ! bash scripts/ci-status.sh --json > "$tmp" 2>/dev/null || ! jq -e . "$tmp" >/dev/null 2>&1; then
    rm -f "$tmp"; echo "── ci-status.sh --json failed — nothing pushed"; exit 3
  fi
  # Local cache first (atomic rename): a shell on the probe box can read the same snapshot
  # the wall is showing, even when the rsync to hp02 hiccups.
  cp "$tmp" /tmp/pi-ci-status.json.tmp && mv /tmp/pi-ci-status.json.tmp /tmp/pi-ci-status.json
  rsync -a --timeout=20 "$tmp" "$DASH_HOST:$DASH_DIR/html/status.json" \
    || { rm -f "$tmp"; echo "── rsync to $DASH_HOST failed"; exit 4; }
  rm -f "$tmp"
  echo "── status.json pushed to $DASH_HOST:$DASH_DIR"
}

case "${1:-}" in
  --deploy)
    ssh -o BatchMode=yes -o ConnectTimeout=10 "$DASH_HOST" "mkdir -p $DASH_DIR/html" \
      || { echo "cannot reach $DASH_HOST"; exit 4; }
    rsync -a --timeout=30 deploy/ci-dashboard/ "$DASH_HOST:$DASH_DIR/" \
      || { echo "deploy rsync failed"; exit 4; }
    # DASH_UID/GID are resolved REMOTELY: the container runs as the hp02 user that owns
    # the bind-mounted site dir, whatever that id happens to be on that box.
    ssh -o BatchMode=yes "$DASH_HOST" \
      "cd $DASH_DIR && DASH_UID=\$(id -u) DASH_GID=\$(id -g) DASH_PORT=$DASH_PORT docker compose up -d" \
      || { echo "remote compose up failed"; exit 5; }
    push_json
    echo "── dashboard up: http://${DASH_HOST#*@}:${DASH_PORT}/"
    ;;
  --install-cron)
    # The cron line bakes in $PWD, so it must live somewhere that still exists in a month.
    # The trap is specifically the agent-scratch worktree tree under .claude/worktrees/:
    # those are created and destroyed per task, so a cron pinned into one keeps running
    # until the directory vanishes and then freezes the board (honestly, as STALE — but
    # uselessly). Same ephemeral-directory shape that froze the demo bind-mounts in #396.
    #
    # A worktree as such is NOT the problem, and refusing all of them was wrong: this repo
    # keeps PERMANENT worktrees outside that tree on purpose (~/workspace/pi-wt-selfheal is
    # the sanctioned demo-deploy checkout, ~/workspace/pi-wt-ci-dash is this probe's), and
    # the primary checkout cannot hold `main` while another worktree does. So gate on the
    # ephemeral LOCATION, not on worktree-ness.
    case "$PWD" in
      */.claude/worktrees/*)
        echo '── refusing: this is an EPHEMERAL agent worktree (.claude/worktrees/).'
        echo '   The cron bakes in $PWD and would point at a directory that disappears'
        echo '   when the worktree is cleaned up. Use the primary checkout or a permanent'
        echo '   worktree under ~/workspace (e.g. ~/workspace/pi-wt-ci-dash).'
        echo "   here: $PWD"
        exit 6
        ;;
    esac
    # Prove the thing works in cron's ENVIRONMENT before installing it, not after: the
    # first version of this cron ran happily from an interactive shell and was a silent
    # no-op under cron (no PAT — see the token block at the top). `env -i` is the closest
    # honest approximation of what cron hands us. A gate that installs a broken job and
    # lets the board rot is worse than no gate.
    # Test THIS script, not ci-status.sh: the token block above lives here, so probing
    # ci-status.sh directly under env -i fails even when the cron would have worked. (It
    # did exactly that on the first install — the guard refused a working setup.) Test the
    # entry point cron will actually invoke, in cron's environment, minus the side effects.
    if ! env -i HOME="$HOME" PATH=/usr/bin:/bin \
         bash -c "cd '$PWD' && bash scripts/ci-dashboard-push.sh --selftest" >/dev/null 2>&1; then
      echo '── refusing: the refresh cannot run in a cron-like environment (env -i).'
      echo '   Usually the GitHub PAT: cron sources no rc file, so it must be resolvable'
      echo '   from ~/.bashrc by the token block in this script. Reproduce with:'
      echo "     env -i HOME=\$HOME PATH=/usr/bin:/bin bash -c \"cd '$PWD' && bash scripts/ci-dashboard-push.sh --selftest\""
      exit 7
    fi
    # Log to a FIXED file, overwritten each cycle: >/dev/null is what made the first broken
    # cron invisible. Overwrite (not append) so it can never grow unbounded, while the last
    # cycle's output is always there to read.
    ( crontab -l 2>/dev/null | grep -v pi-ci-dashboard
      echo "* * * * * cd $PWD && bash scripts/ci-dashboard-push.sh >$CRON_LOG 2>&1 # pi-ci-dashboard"
    ) | crontab -
    echo "── $(hostname -s) crontab now:"; crontab -l | grep pi-ci-dashboard
    ;;
  --uninstall-cron)
    ( crontab -l 2>/dev/null | grep -v pi-ci-dashboard ) | crontab -
    echo "── pi-ci-dashboard cron removed from $(hostname -s)"
    ;;
  --selftest)
    # Render only: no lock, no cache write, no rsync. Exists so --install-cron can prove
    # the real entry point works under env -i without pushing a snapshot as a side effect.
    bash scripts/ci-status.sh --json >/dev/null || exit 3
    ;;
  "") push_json ;;
  *) echo "usage: ci-dashboard-push.sh [--deploy | --install-cron | --uninstall-cron]"; exit 2 ;;
esac
