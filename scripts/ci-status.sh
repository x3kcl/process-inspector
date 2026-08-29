#!/usr/bin/env bash
# ── CI status: one glanceable view of the whole GitHub Actions estate ────────────────
# Answers "what is CI doing right now, and is `main` actually green?" without the half
# dozen hand-typed API probes it used to take: the runner-slot fleet across every CI box,
# `main`'s own check rollup (the green-ci definition of done — a push is NOT success),
# every workflow's newest run, the latest nightly BROKEN OUT PER JOB (a red nightly is
# almost always one job, and knowing which one is the whole triage), and each open PR's
# merge-gate rollup.
#
#   bash scripts/ci-status.sh          # human-readable snapshot
#   bash scripts/ci-status.sh --json   # the same data as JSON (what the web dashboard eats)
#
# Read-only and best-effort throughout: every probe degrades to a null/"(unavailable)"
# section rather than failing the whole view — a dashboard that dies on one bad probe is
# worse than no dashboard. Exit 0 whenever *some* JSON was produced; exit 3 only if we
# could not talk to the API at all.
#
# All timestamps in the JSON are ABSOLUTE epochs. The web page computes ages client-side,
# so if the pusher dies the dashboard renders staleness instead of a frozen "live" view.
#
# Secrets: GITHUB_PERSONAL_ACCESS_TOKEN is read from the environment and never echoed.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 2

REPO="${PI_CI_REPO:-x3kcl/process-inspector}"
# The workflows worth a row, in the order an operator cares about them.
WORKFLOWS="ci.yml nightly.yml publish-edge.yml release.yml"
MAX_PRS="${PI_CI_MAX_PRS:-8}"

[[ -n "${GITHUB_PERSONAL_ACCESS_TOKEN:-}" ]] || {
  echo "ci-status: GITHUB_PERSONAL_ACCESS_TOKEN is not exported" >&2; exit 3; }

# Best-effort GET: prints "null" (valid JSON) on any failure so every jq consumer below
# can keep going. --max-time bounds a hung API against the every-minute cron cadence.
api() {
  curl -fsS --max-time 25 \
    -H "Authorization: Bearer $GITHUB_PERSONAL_ACCESS_TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "https://api.github.com/$1" 2>/dev/null || echo null
}
epoch() { [[ -n "${1:-}" && "$1" != "null" ]] && date -d "$1" +%s 2>/dev/null || echo 0; }

# ── probes ──────────────────────────────────────────────────────────────────────────
# Every response is PROJECTED down to the handful of fields the dashboard uses before it
# is held in a shell variable: the raw commit/check-run/PR payloads run to hundreds of KB
# and the final `jq -n --argjson ...` assembly below blew up with E2BIG ("Argument list
# too long") on the full bodies. Project at the source, not at the sink.
RUNNERS_J="$(api "repos/$REPO/actions/runners?per_page=100" \
  | jq -c '{runners: [ (.runners // [])[] | {name, status, busy} ]}' 2>/dev/null || echo null)"
MAIN_J="$(api "repos/$REPO/commits/main" \
  | jq -c '{sha: (.sha // ""), subject: ((.commit.message // "") | split("\n")[0]),
            author: (.commit.author.name // ""), date: (.commit.author.date // "")}' 2>/dev/null || echo null)"
MAIN_SHA="$(jq -r '.sha // ""' <<<"$MAIN_J")"
PRS_J="$(api "repos/$REPO/pulls?state=open&per_page=$MAX_PRS&sort=updated&direction=desc" \
  | jq -c '[ (. // [])[] | {number, title, draft, url: .html_url, updated_at,
                            head: {sha: .head.sha, ref: .head.ref}} ]' 2>/dev/null || echo null)"

# Rolls a list of workflow RUNS up to one verdict. Runs, not check-runs, on purpose:
# a check-run only carries a job name, while a run carries the WORKFLOW name — and in this
# repo the distinction is the whole point, because `ci.yml` is the merge gate and a red
# `nightly.yml` on the same SHA is explicitly NOT merge-blocking (CLAUDE.md). Rolling them
# together would paint a perfectly mergeable `main` red every morning.
#
# Deliberately pessimistic: any failure wins, then anything still running, then success —
# a dashboard must never round a mixed state up to green.
ROLLUP='
  def latestPerWorkflow:
    # Re-runs and reruns-of-failed-jobs produce several runs per workflow on one SHA;
    # only the newest one is the current verdict.
    (. // []) | sort_by(.updatedEpoch) | group_by(.workflow) | map(last);
  def rollup:
    latestPerWorkflow as $r
    | if ($r | length) == 0 then
        {state:"none", label:"no runs", total:0, failed:[], running:[]}
      else
        ($r | map(select(.conclusion=="failure" or .conclusion=="timed_out"
                         or .conclusion=="cancelled" or .conclusion=="startup_failure"))) as $bad
        | ($r | map(select(.status!="completed"))) as $run
        | {
            total: ($r|length),
            failed: ($bad | map(.workflow)),
            running: ($run | map(.workflow)),
            state:  (if ($bad|length)>0 then "failure"
                     elif ($run|length)>0 then "running"
                     elif ($r | map(select(.conclusion=="success" or .conclusion=="skipped"
                                           or .conclusion=="neutral")) | length) == ($r|length)
                     then "success" else "unknown" end)
          }
        | .label = (if .state=="failure" then "\(.failed|join(", ")) failed"
                    elif .state=="running" then "\(.running|join(", ")) running"
                    elif .state=="success" then "\(.total) workflow(s) green"
                    else "\(.total) run(s)" end)
      end;
'

# All workflow runs for one commit, projected + epoch-stamped. Used for `main`'s gate and
# for every open PR's gate, so the two can never drift apart in meaning.
runs_for_sha() {
  api "repos/$REPO/actions/runs?head_sha=$1&per_page=50" \
    | jq -c '[ (.workflow_runs // [])[]
               | {workflow: (.path | sub("^.*/"; "")), name: .name, number: .run_number,
                  status, conclusion, event, url: .html_url,
                  updatedEpoch: (.updated_at | fromdateiso8601)} ]' 2>/dev/null || echo '[]'
}

MAIN_RUNS="$(runs_for_sha "$MAIN_SHA")"

# Per-workflow newest run. One call each — cheap, and it keeps a slow/renamed workflow
# from blanking the others.
WF_ROWS='[]'
for wf in $WORKFLOWS; do
  r="$(api "repos/$REPO/actions/workflows/$wf/runs?per_page=1")"
  row="$(jq -c --arg wf "$wf" '
      (.workflow_runs // [])[0] as $r
      | if $r == null then {workflow:$wf, present:false}
        else {workflow:$wf, present:true, id:$r.id, number:$r.run_number,
              status:$r.status, conclusion:$r.conclusion, event:$r.event,
              branch:$r.head_branch, sha:($r.head_sha[0:9]),
              url:$r.html_url, created:$r.created_at, updated:$r.updated_at}
        end' <<<"$r" 2>/dev/null || echo "{\"workflow\":\"$wf\",\"present\":false}")"
  # Absolute epochs, computed here (GNU date lives on the probe box, not in the browser).
  c="$(epoch "$(jq -r '.created // ""' <<<"$row")")"
  u="$(epoch "$(jq -r '.updated // ""' <<<"$row")")"
  row="$(jq -c --argjson c "$c" --argjson u "$u" '. + {createdEpoch:$c, updatedEpoch:$u}' <<<"$row")"
  WF_ROWS="$(jq -c --argjson row "$row" '. + [$row]' <<<"$WF_ROWS")"
done

# The nightly, broken out per job — the section that actually shortens a morning triage.
NIGHTLY_ID="$(jq -r '.[] | select(.workflow=="nightly.yml") | .id // empty' <<<"$WF_ROWS")"
NIGHTLY_JOBS='[]'
if [[ -n "$NIGHTLY_ID" ]]; then
  NIGHTLY_JOBS="$(api "repos/$REPO/actions/runs/$NIGHTLY_ID/jobs?per_page=50" | jq -c '
    [ (.jobs // [])[] | {
        name, status, conclusion, url:.html_url,
        runner:(.runner_name // ""),
        # The failing STEP is the single most useful field in a red nightly — surface it
        # so the page never makes you open the log just to learn which gate broke.
        failedStep: ([ (.steps // [])[]
                       | select(.conclusion=="failure" or .conclusion=="timed_out")
                       | .name ] | first // ""),
        started:.started_at, completed:.completed_at } ]' 2>/dev/null || echo '[]')"
  # Durations, resolved here for the same reason as above.
  NIGHTLY_JOBS="$(jq -c '.' <<<"$NIGHTLY_JOBS")"
  tmp='[]'
  while IFS= read -r job; do
    [[ -z "$job" ]] && continue
    s="$(epoch "$(jq -r '.started // ""' <<<"$job")")"
    e="$(epoch "$(jq -r '.completed // ""' <<<"$job")")"
    tmp="$(jq -c --argjson j "$job" --argjson s "$s" --argjson e "$e" \
        '. + [$j + {startedEpoch:$s, completedEpoch:$e,
                    durationSec: (if $s>0 and $e>0 then $e-$s else 0 end)}]' <<<"$tmp")"
  done < <(jq -c '.[]' <<<"$NIGHTLY_JOBS")
  NIGHTLY_JOBS="$tmp"
fi

# Open PRs + each head's gate rollup. Capped at $MAX_PRS so the per-minute cron can never
# fan out into a rate-limit problem on a busy day.
PR_ROWS='[]'
while IFS= read -r pr; do
  [[ -z "$pr" ]] && continue
  sha="$(jq -r '.head.sha' <<<"$pr")"
  roll="$(runs_for_sha "$sha" | jq -c "$ROLLUP rollup" 2>/dev/null || echo 'null')"
  ue="$(epoch "$(jq -r '.updated_at' <<<"$pr")")"
  PR_ROWS="$(jq -c --argjson pr "$pr" --argjson roll "${roll:-null}" --argjson ue "$ue" '
      . + [{number:$pr.number, title:$pr.title, branch:$pr.head.ref,
            sha:($pr.head.sha[0:9]), draft:$pr.draft, url:$pr.html_url,
            updatedEpoch:$ue, gate:$roll}]' <<<"$PR_ROWS")"
done < <(jq -c '(. // []) | .[]' <<<"$PRS_J" 2>/dev/null)

# ── assemble ────────────────────────────────────────────────────────────────────────
# Runner names are "<box>-docker-s<N>" (scripts/ci-runner.sh) — split on that so the page
# can group slots by the box that owns them.
JSON="$(jq -n \
  --arg host "$(hostname -s)" \
  --arg repo "$REPO" \
  --argjson now "$(date +%s)" \
  --argjson runners "${RUNNERS_J:-null}" \
  --argjson mainCommit "${MAIN_J:-null}" \
  --argjson mainRuns "${MAIN_RUNS:-[]}" \
  --argjson workflows "$WF_ROWS" \
  --argjson nightlyJobs "$NIGHTLY_JOBS" \
  --argjson prs "$PR_ROWS" \
  "$ROLLUP"'
  {
    host: $host, repo: $repo, generatedEpoch: $now,
    main: {
      sha: (($mainCommit.sha // "")[0:9]),
      subject: ($mainCommit.subject // ""),
      author: ($mainCommit.author // ""),
      dateEpoch: (($mainCommit.date // "") | if . == "" then 0 else fromdateiso8601 end),
      # Two verdicts, never conflated: `gate` is the green-ci definition of done (ci.yml
      # on this SHA), `allGate` folds in the non-blocking nightly/publish workflows.
      gate: ([ $mainRuns[] | select(.workflow == "ci.yml") ] | rollup),
      allGate: ($mainRuns | rollup),
      runs: ($mainRuns | latestPerWorkflow | sort_by(.workflow))
    },
    slots: [ (($runners.runners // [])[])
             | { name, status, busy,
                 box: (.name | capture("^(?<b>[a-z0-9]+)-docker-s") | .b // "other"),
                 slot: (.name | capture("-s(?<n>[0-9]+)$") | .n // "?") } ]
           | sort_by(.box, .slot),
    workflows: $workflows,
    nightlyJobs: $nightlyJobs,
    prs: $prs
  }
  | .boxes = ( .slots | group_by(.box) | map({
        name: .[0].box,
        total: length,
        online: (map(select(.status=="online")) | length),
        busy:   (map(select(.busy)) | length)
      }) )
  ')"

if [[ -z "$JSON" ]] || ! jq -e . >/dev/null 2>&1 <<<"$JSON"; then
  echo "ci-status: could not assemble a snapshot" >&2; exit 3
fi

if [[ "${1:-}" == "--json" ]]; then
  printf '%s\n' "$JSON"
  exit 0
fi

# ── human renderer — same data, no second source of truth ───────────────────────────
jq -r '
  def age(e): if e == 0 then "?" else (now - e) as $s
    | if $s < 120 then "\($s|floor)s" elif $s < 7200 then "\(($s/60)|floor)m"
      else "\(($s/3600)|floor)h" end end;
  def pad(n): (. + (" " * 40))[0:n];
  "══ process-inspector CI — probed from \(.host) — \(now | strftime("%F %T")) ══",
  "",
  "main     \(.main.sha)  \(.main.subject)",
  "         merge gate (ci.yml)  \(.main.gate.state | ascii_upcase) — \(.main.gate.label)",
  "         all workflows        \(.main.allGate.state | ascii_upcase) — \(.main.allGate.label)",
  "",
  "runner slots",
  ( .boxes[]? | "  \(.name | pad(8)) \(.online)/\(.total) online, \(.busy) busy" ),
  ( if (.boxes | length) == 0 then "  (none registered)" else empty end),
  "",
  "workflows",
  ( .workflows[]
    | "  \(.workflow | pad(18)) " +
      (if .present then "#\(.number) \((.conclusion // .status) | ascii_upcase | pad(12)) \(age(.updatedEpoch)) ago  \(.branch) \(.sha)"
       else "(no runs)" end) ),
  "",
  "nightly jobs (newest run)",
  ( .nightlyJobs[]? | "  \(.name | pad(26)) \((.conclusion // .status) | ascii_upcase | pad(12))" +
      (if .failedStep != "" then "  ↳ \(.failedStep)" else "" end) ),
  ( if (.nightlyJobs | length) == 0 then "  (no nightly run found)" else empty end),
  "",
  "open pull requests",
  ( .prs[]? | "  #\(.number | tostring | pad(5)) \(.gate.state | ascii_upcase | pad(9)) \(.gate.label | pad(14)) \(.title)" ),
  ( if (.prs | length) == 0 then "  (none)" else empty end)
' <<<"$JSON"
