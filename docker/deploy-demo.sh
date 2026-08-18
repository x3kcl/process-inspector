#!/usr/bin/env bash
# Deploy (or roll back, via an older IMAGE_TAG) pi.naumann.cloud to a digest-pinned image
# build.
#
# Usage:
#   docker/deploy-demo.sh [IMAGE_TAG]              # default: edge. Also sha-<short7>/X.Y.Z
#                                                   # (the published IMAGE tag has no "v" —
#                                                   # docker/metadata-action strips it from
#                                                   # the vX.Y.Z git/release tag; issue #200).
#   docker/deploy-demo.sh --dry-run [IMAGE_TAG]     # resolve + print, no compose/commit/tag
#   docker/deploy-demo.sh --allow-engine-recreate [IMAGE_TAG]
#   docker/deploy-demo.sh --allow-topology-drift [IMAGE_TAG]   # see the #370 guard below
#                                                   # override the issue #377 engine-recreate
#                                                   # guard below. Flags combine in any order.
#
# What it does (issue #92 — demo compose pinned by digest, never a floating tag, and every
# deploy attributable to a SHA):
#   1. Resolves the current digest of IMAGE_TAG for both published images via
#      `docker buildx imagetools inspect` (reads registry metadata only — no pull).
#   2. Writes PI_BFF_DIGEST/PI_WEB_DIGEST into docker/.env.demo.
#   3. `docker compose ... pull && up -d` — the running containers now match that digest.
#   4. Commits docker/.env.demo and tags the commit `demo-YYYY-MM-DD-<shortsha>` — the git
#      history of that one file is the attribution record for "what's running right now".
#      Does NOT push (see the printed instructions) — publishing the tag/commit is a
#      separate, deliberate step.
#
# Rollback = docker/rollback-demo.sh <demo-tag> (restores a PRIOR deploy's exact pinned
# digest pair from git history — see that script and RUNBOOK.md §8 for the drilled
# procedure). This script only ever RESOLVES a tag's CURRENT digest, which is only safe going
# forward — rollback-demo.sh is the one that's correct for "go back to exactly what was
# running before" (a floating tag like `edge` may have moved since).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/docker/docker-compose.demo.yml"
ENV_FILE="$REPO_ROOT/docker/.env.demo"
BFF_IMAGE="ghcr.io/x3kcl/process-inspector-bff"
WEB_IMAGE="ghcr.io/x3kcl/process-inspector-web"
DIGEST_RE='^sha256:[0-9a-f]{64}$'

# Fail with a message on stderr. assert_no_topology_drift already called this; nothing
# defined it, so under `set -e` that path aborted with "die: command not found" instead.
die() {
  echo "$*" >&2
  exit 1
}

# issue #377 — engine-recreate guard. The demo engines now keep their state on a named
# volume (docker-compose.demo.yml), but recreating one is still exactly the moment that
# destroyed 16 days of pilot history on 2026-08-05: a `--force-recreate` run against
# engine-a/engine-b/engine-7 to repair DNS aliases (nothing to do with engine data) left them
# healthy and EMPTY, because at the time there was no volume at all. This script never
# targets the engines today — both `up` calls below are explicitly scoped to backend/frontend
# and the three backup sidecars — but `compose_up_guarded` (below) refuses ANY call from this
# script that would recreate an engine, or that omits a service list entirely (which
# `docker compose up` treats as "every service", engines included), unless
# `--allow-engine-recreate` is passed. This is deliberately paranoid: it protects against a
# future edit widening one of these calls, not just today's code.
ENGINE_SERVICES=(engine-a engine-b engine-7)
ALLOW_ENGINE_RECREATE=0

# issue #370 — TOPOLOGY-DRIFT guard. This script updates digest-pinned IMAGES; it deliberately
# does NOT apply topology changes, because every `up` below is service-scoped (see the notes on
# `postgres` and the engines). That scoping is correct, but until now it was SILENT: when #368
# added an `engine-7` service to the compose file, the deploy printed its success banner,
# passed the /api/engines 401 chain check (the BFF is up either way), committed `.env.demo` and
# TAGGED the release — while the new container was never created. The demo was left
# half-applied with its attribution record (that file's git history) claiming otherwise.
#
# So: before mutating anything, compare the compose file's service list against what the
# project is actually running, and REFUSE on a mismatch with instructions. We refuse rather
# than widening to a bare `up -d` (issue #370 option 2) for two measured reasons: an unscoped
# `up -d` re-runs the one-shot `seed` service, whose instance starts are NOT idempotent by
# design (docker/seed.sh header), and on 2026-08-05 an unscoped `up -d` on this very stack
# recreated the `internal` network, dropped the engines' compose DNS aliases, and the
# --force-recreate used to repair that destroyed 16 days of pilot history. Converging topology
# is an operator decision with real blast radius, not a side effect of a digest bump.
TOPOLOGY_DRIFT_OK=0

assert_no_topology_drift() {
  [[ "$TOPOLOGY_DRIFT_OK" == 1 ]] && return 0
  local declared running missing extra
  declared="$(docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" config --services 2>/dev/null | sort)"
  # `ps --services` lists services with containers for THIS project, running or not; a
  # one-shot service that has already exited still counts as applied, which is what we want
  # (`seed` must not be reported as missing just because it did its job and stopped).
  running="$(docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" ps --services --all 2>/dev/null | sort)"
  if [[ -z "$declared" ]]; then
    die "could not read the compose service list — refusing to deploy blind"
  fi
  missing="$(comm -23 <(printf '%s\n' "$declared") <(printf '%s\n' "$running") | tr '\n' ' ' | sed 's/ *$//')"
  extra="$(comm -13 <(printf '%s\n' "$declared") <(printf '%s\n' "$running") | tr '\n' ' ' | sed 's/ *$//')"
  if [[ -n "$missing" || -n "$extra" ]]; then
    echo "REFUSING: the running topology does not match $COMPOSE_FILE." >&2
    [[ -n "$missing" ]] && echo "  declared but NOT created: $missing" >&2
    [[ -n "$extra" ]] && echo "  running but no longer declared: $extra" >&2
    cat >&2 <<'DRIFT'

This script applies digest-pinned IMAGE updates only — its `up` calls are service-scoped and
do NOT create or remove services (issue #370). Deploying now would print success, commit
docker/.env.demo and tag the release while the running stack did not match the file it claims
to be deployed from, corrupting that file's git history as the attribution record.

Converge the topology deliberately, then re-run this script. Note the hazards first:
  * a bare `docker compose ... up -d` re-runs the one-shot `seed` service, and instance starts
    are NOT idempotent (docker/seed.sh) — every run adds one instance per status arc per engine;
  * it can also recreate the `internal` network. On 2026-08-05 that dropped the engines'
    compose DNS aliases, and the --force-recreate used to repair it destroyed 16 days of
    pilot ledger history (issues #370, #377).
  * prefer naming the specific new/removed services, e.g.
      docker compose -f docker/docker-compose.demo.yml --env-file docker/.env.demo up -d <svc>
    and re-check `docker compose ... ps --services --all` afterwards.

Override with --allow-topology-drift only if you have already reconciled it by hand and
understand that the tag this script writes will claim a state you are asserting, not one it
verified.
DRIFT
    exit 1
  fi
}

# issue #396 — EPHEMERAL-CHECKOUT guard. docker-compose.demo.yml mounts the backup machinery
# by RELATIVE path (`./backup/audit-backup:/scripts:ro`, `./backup/postgres/pg_hba-demo.conf:…`),
# and compose resolves those against the COMPOSE FILE's directory — so whichever checkout this
# script runs from is frozen into the live containers' bind-mount sources for as long as those
# containers exist, long after the deploy returns.
#
# On 2026-08-08 a deploy was run from `.claude/worktrees/cursor-demo-refresh-f543/`. That
# worktree is auto-cleaned, and when it went away the four containers holding mounts into it
# lost their scripts: `audit-backup` and `audit-basebackup` failed every cron run (no logical
# dump between 2026-08-07 and 2026-08-18, no basebackup since 2026-08-02), and `postgres` was
# left a restart-bomb — on its next restart Docker recreates the missing pg_hba source as an
# empty root-owned DIRECTORY, `hba_file` then points at a directory, and the demo database
# refuses to start. Nothing about the deploy itself looked wrong at the time.
#
# So: refuse to deploy from an auto-cleaned worktree. Unlike the #377/#370 guards there is
# deliberately NO override flag — an override would freeze the mounts anyway, which is the
# entire harm, and the remedy (re-run from the durable checkout) is always available and
# always correct.
assert_durable_checkout() {
  case "$REPO_ROOT" in
    */.claude/worktrees/*) ;;
    *) return 0 ;;
  esac
  local durable
  echo "REFUSING: this is an ephemeral worktree — $REPO_ROOT" >&2
  # `git worktree list` prints the MAIN working tree first — that is the durable checkout by
  # definition (linked worktrees can be pruned; the main one cannot). awk (not `head`) reads
  # the pipe to EOF: `head -1` closes it early, and under `set -o pipefail` git's resulting
  # SIGPIPE (141) would abort this guard before it could print anything. `|| durable=""` keeps
  # a git failure from doing the same — the hint is a nicety, the refusal is the point.
  durable="$(git -C "$REPO_ROOT" worktree list 2>/dev/null | awk 'NR == 1 { print $1 }')" || durable=""
  cat >&2 <<'EPHEMERAL'

docker-compose.demo.yml binds the backup machinery by RELATIVE path, so compose would freeze
THIS directory into the live containers as their bind-mount source. `.claude/worktrees/*` is
auto-cleaned: when it is removed the mounts dangle, audit-backup/audit-basebackup fail every
cron run, and postgres will not start after its next restart (issue #396 — that outage cost
11 days of demo audit backups).
EPHEMERAL
  if [[ -n "$durable" ]]; then
    echo "Re-run the deploy from the durable checkout instead:" >&2
    echo "  cd $durable && docker/deploy-demo.sh $*" >&2
  else
    echo "Re-run the deploy from a durable (non-worktree) clone instead." >&2
  fi
  exit 1
}

# compose_up_guarded ARGS... — same argument shape as `docker compose ... up ARGS...`. Refuses
# (exit 1) if ARGS names an engine service, or names no service at all, unless
# --allow-engine-recreate was passed on this script's own command line. See the guard note
# above for why.
compose_up_guarded() {
  local -a services=()
  local arg svc engine
  for arg in "$@"; do
    case "$arg" in
      -*) ;; # a flag (-d, --force-recreate, ...), not a service name
      *) services+=("$arg") ;;
    esac
  done
  if [[ "$ALLOW_ENGINE_RECREATE" != "1" ]]; then
    if [[ "${#services[@]}" -eq 0 ]]; then
      echo "REFUSING: 'docker compose up' with no service list recreates EVERY service," >&2
      echo "including the engines (issue #377). Pass --allow-engine-recreate to override." >&2
      exit 1
    fi
    for svc in "${services[@]}"; do
      for engine in "${ENGINE_SERVICES[@]}"; do
        if [[ "$svc" == "$engine" ]]; then
          echo "REFUSING: this would recreate '$engine'." >&2
          echo "flowable-rest keeps its process/job/history state in a container-scoped H2" >&2
          echo "store on a named volume (docker-compose.demo.yml, issue #377) — a recreate is" >&2
          echo "safe against ordinary config drift but this script should never do it as a" >&2
          echo "side effect of an unrelated backend/frontend deploy. Pass" >&2
          echo "--allow-engine-recreate if recreating '$engine' is actually intended." >&2
          exit 1
        fi
      done
    done
  fi
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up "$@"
}

DRY_RUN=0
while [[ "${1:-}" == --* ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --allow-engine-recreate) ALLOW_ENGINE_RECREATE=1; shift ;;
    --allow-topology-drift) TOPOLOGY_DRIFT_OK=1; shift ;;
    *)
      echo "unknown flag: $1" >&2
      exit 1
      ;;
  esac
done
IMAGE_TAG="${1:-edge}"

# #396: refuse before doing any work at all — see the guard note above.
assert_durable_checkout "$IMAGE_TAG"

# The published image tag never carries the git/release tag's leading "v" (docker/
# metadata-action's {{version}} pattern strips it) — a "vX.Y.Z" IMAGE_TAG will always 404
# regardless of whether that version actually published, which is exactly what produced
# issue #200's false "images never published" report. Catch the mistake with a clear
# pointer to the fix instead of a generic registry "not found".
if [[ "$IMAGE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+ ]]; then
  echo "IMAGE_TAG '$IMAGE_TAG' looks like a git/release tag, not a published image tag." >&2
  echo "Published image tags drop the leading 'v' — try '${IMAGE_TAG#v}' instead." >&2
  exit 1
fi

resolve_digest() {
  local digest
  digest="$(docker buildx imagetools inspect "$1:$IMAGE_TAG" --format '{{json .Manifest}}' | jq -r '.digest')"
  # jq -r prints the literal string "null" (exit 0) if .digest is absent — plain `set -e`
  # would not catch that, and a "null" digest would otherwise be written straight into
  # docker/.env.demo and only fail much later, opaquely, at `docker compose pull`.
  if [[ ! "$digest" =~ $DIGEST_RE ]]; then
    echo "resolved digest for $1:$IMAGE_TAG doesn't look like a digest: '$digest'" >&2
    exit 1
  fi
  echo "$digest"
}

echo "Resolving digests for tag '$IMAGE_TAG'..."
BFF_DIGEST="$(resolve_digest "$BFF_IMAGE")"
WEB_DIGEST="$(resolve_digest "$WEB_IMAGE")"
echo "  $BFF_IMAGE@$BFF_DIGEST"
echo "  $WEB_IMAGE@$WEB_DIGEST"

if [[ "$DRY_RUN" == "1" ]]; then
  echo "(--dry-run: not touching docker/.env.demo, not deploying, not tagging)"
  exit 0
fi

# #370: refuse BEFORE the first mutation — .env.demo is the attribution record, so it must
# not be rewritten for a deploy that cannot fully apply.
assert_no_topology_drift

sed -i.bak \
  -e "s|^PI_BFF_DIGEST=.*|PI_BFF_DIGEST=$BFF_DIGEST|" \
  -e "s|^PI_WEB_DIGEST=.*|PI_WEB_DIGEST=$WEB_DIGEST|" \
  "$ENV_FILE"
rm -f "$ENV_FILE.bak"

# #396: make the frozen path visible in the deploy log. Every relative bind mount in
# docker-compose.demo.yml resolves against this directory and is baked into the containers
# it creates — if this line ever names a path that can be deleted, the deploy is the bug.
echo "Compose bind-mount root (frozen into the containers): $REPO_ROOT/docker"
echo "Pulling + redeploying..."
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" pull backend frontend
# `up -d` is scoped, deliberately excluding `postgres` — NOT a blanket `up -d`. Since issue
# #201, docker-compose.demo.yml's `postgres` service carries a `command:`/pg_hba override that
# isn't yet applied to the live container; an unscoped `up -d` would detect that config drift
# on THIS routine digest-bump deploy and silently recreate (restart) postgres as a side
# effect — exactly the un-deliberate activation issue #201's own docs say must not happen.
# Activating it is a separate, explicit `up -d postgres` step (see deploy/README.md
# "Activating Docker-native backups").
#
# The three Docker-native backup sidecars (issue #201-followup — audit-backup,
# audit-basebackup, wal-receiver) ARE included here, unlike postgres: they don't carry
# postgres's "restart disrupts live traffic" risk (no client ever connects to them; they only
# connect OUT to postgres), so routinely reconciling them on every deploy is safe and
# desirable rather than something to guard against — wal-receiver's restart-then-resume
# behavior is exactly the property this PR's own rehearsal proved safe (see
# docs/IMPLEMENTATION-PLAN.md's #201-followup entry).
#   --force-recreate, scoped to just these three: `docker compose up -d` only recreates a
# service whose CONFIG HASH changed (image/env/volume list) — since these three mount their
# scripts via a static bind-mount path (`./backup/...:/scripts:ro`), an edit to backup-once.sh/
# crontab/entrypoint.sh content alone does NOT change that hash, so without --force-recreate a
# routine deploy would silently keep running the OLD script content (review finding). backend/
# frontend deliberately do NOT get --force-recreate — their digest pin already changes the
# image reference itself, which IS a config-hash change compose picks up on its own.
compose_up_guarded -d backend frontend
compose_up_guarded -d --force-recreate audit-backup audit-basebackup wal-receiver

echo "Verifying (expect 401 = chain healthy)..."
sleep 5
CODE="$(curl -s -o /dev/null -w '%{http_code}' https://pi.naumann.cloud/api/engines || echo "curl-failed")"
echo "  https://pi.naumann.cloud/api/engines -> $CODE"
if [[ "$CODE" != "401" ]]; then
  # Fail BEFORE committing — an unverified deploy must never become "the" attribution
  # record. Containers are already running the new images; docker/.env.demo is left
  # modified-but-uncommitted for a human to inspect (git diff still shows the intended
  # pin), same posture as rollback-demo.sh's identical check.
  echo "ERROR: expected 401, got $CODE — see docker/DEMO-DEPLOY.md#troubleshooting. Not committing/tagging this deploy." >&2
  exit 1
fi

git -C "$REPO_ROOT" add docker/.env.demo
if git -C "$REPO_ROOT" diff --cached --quiet -- docker/.env.demo; then
  echo "docker/.env.demo unchanged — '$IMAGE_TAG' already resolves to what's currently pinned. Nothing to commit/tag."
  exit 0
fi
git -C "$REPO_ROOT" commit -m "chore(demo): deploy $IMAGE_TAG (bff@${BFF_DIGEST:7:12} web@${WEB_DIGEST:7:12})"
# Tag AFTER the commit, from the commit it actually names — computing this before the
# commit would embed the PARENT sha in a tag that resolves to the new commit.
TAG="demo-$(date +%Y-%m-%d)-$(git -C "$REPO_ROOT" rev-parse --short HEAD)"
git -C "$REPO_ROOT" tag -a "$TAG" -m "demo deploy: $IMAGE_TAG"

cat <<EOF

Deployed and committed locally as $TAG. To publish the attribution record:
  git -C "$REPO_ROOT" push origin HEAD "$TAG"

To roll back to exactly this state later:
  docker/rollback-demo.sh $TAG
EOF
