#!/usr/bin/env bash
# pilot-data-pull.sh — ONE read-only pull of the demo audit/ledger store for #479 and #480.
#
# Why: the S0 playbook-demand re-check (#479, input to #106) and the attention-ordering
# decision memo (#480) both need data that only the demo Postgres holds, and an agent
# session may not exec into it. The operator runs this once; the review files are then
# written from the output folder without another round-trip.
#
# What it does: `docker exec -i <container> psql ...` — credentials come from the
# container's OWN environment (POSTGRES_USER / POSTGRES_PASSWORD / POSTGRES_DB, expanded
# inside the container, never on this host's command line). EVERY statement runs inside
# BEGIN READ ONLY with ON_ERROR_STOP and a statement timeout; nothing is written to the
# database. Results are plain CSV/text files in the output folder.
#
# Usage:
#   scripts/pilot-data-pull.sh [OUTDIR]          # default OUTDIR: ./pilot-data-<UTC date>/
#   PILOT_PG_CONTAINER=name  override the container   (default process-inspector-demo-postgres-1)
#   PILOT_PG_USER / PILOT_PG_DB  override the role / database (default: the container's own)
#
# Output (see docs/OPERATIONS.md "Pilot data pull" for what to do with it):
#   00-run-meta.txt, 01-table-spans.csv                    row counts + min/max timestamps per table
#   playbook/10-demand-raw.csv                              scripts/mine-playbook-demand.sql as is
#   playbook/11-demand-excl-seed.csv                        same, demo-seed window excluded
#   playbook/12-audit-actions.csv, 13-seed-window-rows.csv  context for the exclusion
#   ledger/20-era-trusted-span.csv, 21-g5-current-era.csv   G5 (ALARM-COST-MODEL §7 / §16.7)
#   ledger/22-g1-closed-episodes.csv, 23-g2-concurrency.csv G1 / G2
#   ledger/24-g4-acks.csv, 25-retrying-spells.csv           G4 / R2 spell inputs
#   ledger/26-triage-snapshot-spans.csv                     count-only ordering input (inventory)
#   ledger/30-incident.csv, 31-incident-episode.csv, 32-incident-occurrence.csv, 33-bulk-job.csv
#                                                           raw inputs for attention-harm-search /
#                                                           replay-burst-attention (no free text
#                                                           except the normalized message)
# Exit status 1 if any single query failed (the others still run; failures are listed).
set -euo pipefail

CONTAINER="${PILOT_PG_CONTAINER:-process-inspector-demo-postgres-1}"
OUT="${1:-./pilot-data-$(date -u +%F)}"
DIR="$(cd "$(dirname "$0")" && pwd)"
# presentation/DEMO-SEED-WINDOW.md — keep in sync with that file
SEED_FROM='2026-09-14T15:02:30Z'
SEED_TO='2026-09-14T15:40:00Z'
SEED_MARKER='PAY-(OK|FIX)-'

mkdir -p "$OUT/playbook" "$OUT/ledger"
failed=()

# psql inside the container; $POSTGRES_* expand there. "$@" = extra psql args.
dpsql() {
  docker exec -i ${PILOT_PG_USER:+-e "PILOT_PG_USER=$PILOT_PG_USER"} ${PILOT_PG_DB:+-e "PILOT_PG_DB=$PILOT_PG_DB"} "$CONTAINER" \
    sh -c 'PGPASSWORD="${POSTGRES_PASSWORD:-}" exec psql -X -q -v ON_ERROR_STOP=1 -U "${PILOT_PG_USER:-$POSTGRES_USER}" -d "${PILOT_PG_DB:-${POSTGRES_DB:-$POSTGRES_USER}}" "$@"' sh "$@"
}

# run <outfile> : SQL on stdin, wrapped in a READ ONLY transaction, CSV out.
run() {
  local f="$OUT/$1" sql; sql="$(cat)"
  if { printf 'BEGIN READ ONLY;\nSET LOCAL statement_timeout = %s;\n%s\nCOMMIT;\n' "'300s'" "$sql" | dpsql --csv -f - >"$f.tmp"; }; then
    mv "$f.tmp" "$f"; printf 'ok   %-42s %s rows\n' "$1" "$(($(wc -l <"$f") - 1))"
  else
    rm -f "$f.tmp"; failed+=("$1"); printf 'FAIL %s\n' "$1" >&2
  fi
}

{
  echo "pulled_utc: $(date -u +%FT%TZ)"
  echo "container:  $CONTAINER"
  echo "script_rev: $(git -C "$DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  echo "seed_window: $SEED_FROM .. $SEED_TO (presentation/DEMO-SEED-WINDOW.md)"
  printf 'server:     '; echo 'BEGIN READ ONLY; SELECT version();' | dpsql -At -f - | head -1
} >"$OUT/00-run-meta.txt"

# ---- table spans: makes the usable span visible -------------------------------------------
run 01-table-spans.csv <<'SQL'
SELECT t, rows, min_ts, max_ts, now() AS pulled_at FROM (
  SELECT 'audit_entry' t, count(*) rows, min(ts) min_ts, max(ts) max_ts FROM audit_entry
  UNION ALL SELECT 'incident_occurrence', count(*), min(sampled_at), max(sampled_at) FROM incident_occurrence
  UNION ALL SELECT 'incident', count(*), min(first_seen), max(last_seen) FROM incident
  UNION ALL SELECT 'incident_episode', count(*), min(started_at), max(coalesce(ended_at, started_at)) FROM incident_episode
  UNION ALL SELECT 'triage_snapshot', count(*), min(sampled_at), max(sampled_at) FROM triage_snapshot
  UNION ALL SELECT 'bulk_job', count(*), min(submitted_at), max(submitted_at) FROM bulk_job
  UNION ALL SELECT 'error_group_ack', count(*), min(acknowledged_at), max(acknowledged_at) FROM error_group_ack
) s ORDER BY t;
SQL

# ---- #479: playbook demand ----------------------------------------------------------------
# (a) the committed query, byte for byte
run playbook/10-demand-raw.csv <"$DIR/mine-playbook-demand.sql"

# (b) same query, seed window excluded. Derived by one textual substitution so the two can
# never drift apart; the audit row carries no business key, so the PAY-OK-/PAY-FIX- markers
# can only match payload/response text — the exclusion therefore drops (1) every row inside
# the window and (2) EVERY row of any instance touched inside the window or carrying a marker,
# so no half-excluded sequence is ever mined. Aggregates still come from the same filtered set.
excl_sql="$(sed "s#^      AND outcome = 'ok'\$#      AND outcome = 'ok'\n      AND NOT (ts >= timestamptz '$SEED_FROM' AND ts <= timestamptz '$SEED_TO')\n      AND (engine_id, instance_id) NOT IN (SELECT engine_id, instance_id FROM audit_entry WHERE instance_id IS NOT NULL AND ((ts >= timestamptz '$SEED_FROM' AND ts <= timestamptz '$SEED_TO') OR payload::text ~ '$SEED_MARKER' OR response_snippet ~ '$SEED_MARKER'))#" "$DIR/mine-playbook-demand.sql")"
if [ "$excl_sql" = "$(cat "$DIR/mine-playbook-demand.sql")" ]; then
  echo "FAIL: seed-window exclusion did not apply (mine-playbook-demand.sql changed shape)" >&2; failed+=(11-demand-excl-seed.csv)
else
  printf '%s\n' "$excl_sql" | run playbook/11-demand-excl-seed.csv
fi

run playbook/12-audit-actions.csv <<'SQL'
SELECT action, outcome, count(*) AS rows, count(DISTINCT (engine_id, instance_id)) AS instances,
       min(ts) AS first_ts, max(ts) AS last_ts
FROM audit_entry GROUP BY action, outcome ORDER BY rows DESC, action, outcome;
SQL

run playbook/13-seed-window-rows.csv <<SQL
SELECT actor, action, outcome, count(*) AS rows, count(DISTINCT (engine_id, instance_id)) AS instances,
       min(ts) AS first_ts, max(ts) AS last_ts,
       (ts >= timestamptz '$SEED_FROM' AND ts <= timestamptz '$SEED_TO') AS in_window
FROM audit_entry
WHERE (ts >= timestamptz '$SEED_FROM' AND ts <= timestamptz '$SEED_TO')
   OR payload::text ~ '$SEED_MARKER' OR response_snippet ~ '$SEED_MARKER'
GROUP BY actor, action, outcome, in_window ORDER BY rows DESC, actor, action;
SQL

# ---- #480: attention-ordering decision inputs ---------------------------------------------
# G5 (ALARM-COST-MODEL §7/§16.7): trusted span of the CURRENT ERA. A pass is one sampled_at.
# Era boundary = fleet differs from the previous pass's; a run of '' (unrecorded, pre-V22) passes is
# ONE era with fleet NULL, comparable to nothing, so its trusted span does not count. Trusted =
# every row of the pass is cycle_complete AND NOT truncated.
read -r -d '' ERA_CTE <<'SQL' || true
passes AS (
  SELECT sampled_at, min(fleet) AS fmin, max(fleet) AS fmax,
         bool_and(cycle_complete AND NOT truncated) AS trusted
  FROM incident_occurrence GROUP BY sampled_at
), marked AS (
  SELECT *, (fmin <> fmax OR fmax IS DISTINCT FROM lag(fmax) OVER (ORDER BY sampled_at)) AS boundary
  FROM passes
), eras AS (
  SELECT *, sum(boundary::int) OVER (ORDER BY sampled_at) AS era FROM marked
)
SQL

run ledger/20-era-trusted-span.csv <<SQL
WITH $ERA_CTE
SELECT era, nullif(max(fmax), '') AS fleet, min(sampled_at) AS first_pass, max(sampled_at) AS last_pass,
       count(*) AS passes, count(*) FILTER (WHERE trusted) AS trusted_passes,
       min(sampled_at) FILTER (WHERE trusted) AS first_trusted_pass,
       round((extract(epoch FROM max(sampled_at) - min(sampled_at) FILTER (WHERE trusted)) / 86400)::numeric, 2) AS trusted_span_days
FROM eras GROUP BY era ORDER BY era;
SQL

run ledger/21-g5-current-era.csv <<SQL
WITH $ERA_CTE, cur AS (SELECT max(era) AS era FROM eras)
SELECT nullif(max(fmax), '') AS fleet, (max(fmax) <> '') AS fleet_recorded,
       min(sampled_at) FILTER (WHERE trusted) AS era_first_trusted_pass, max(sampled_at) AS last_pass, now() AS pulled_at,
       CASE WHEN max(fmax) <> '' THEN round((extract(epoch FROM now() - min(sampled_at) FILTER (WHERE trusted)) / 86400)::numeric, 2) ELSE 0 END AS trusted_span_days_to_now,
       56 AS g5_required_days,
       -- largest gap between consecutive passes inside the era (minutes): the blind/outage thinning
       (SELECT round(max(extract(epoch FROM d) / 60)::numeric, 1) FROM (
          SELECT sampled_at - lag(sampled_at) OVER (ORDER BY sampled_at) AS d FROM eras WHERE era = (SELECT era FROM cur)) g) AS max_gap_minutes
FROM eras WHERE era = (SELECT era FROM cur);
SQL

run ledger/22-g1-closed-episodes.csv <<'SQL'
SELECT i.id AS incident_id, i.signature_hash, i.algo_version, i.exception_class, i.state,
       count(e.id) AS episodes, count(e.ended_at) AS closed_episodes,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM e.ended_at - e.started_at)) FILTER (WHERE e.ended_at IS NOT NULL) AS closed_median_s
FROM incident i LEFT JOIN incident_episode e ON e.incident_id = i.id
GROUP BY i.id ORDER BY closed_episodes DESC, i.id;
SQL

run ledger/23-g2-concurrency.csv <<'SQL'
WITH per_pass AS (
  SELECT sampled_at, count(DISTINCT incident_id) FILTER (WHERE total > 0) AS live_classes
  FROM incident_occurrence WHERE sampled_at >= now() - interval '28 days' GROUP BY sampled_at
)
SELECT date_trunc('day', sampled_at) AS day, max(live_classes) AS max_concurrent_live_classes, count(*) AS passes
FROM per_pass GROUP BY 1 ORDER BY 1;
SQL

run ledger/24-g4-acks.csv <<'SQL'
SELECT signature_hash, algo_version, engine_id, definition_key, acknowledged_by, ticket_id, acknowledged_at,
       expires_at, acknowledged_count, (expires_at IS NOT NULL AND expires_at < now()) AS expired
FROM error_group_ack ORDER BY acknowledged_at, signature_hash;
SQL

# R2 RETRYING spells (RETRYING-RISK-LANE): maximal runs of occurrence rows with retrying_count > 0
# per incident; ESCALATED if dead_letter_count grew over the spell, closed if a later row exists.
run ledger/25-retrying-spells.csv <<'SQL'
WITH o AS (
  SELECT incident_id, sampled_at, dead_letter_count, retrying_count, cycle_complete, truncated,
         row_number() OVER w AS rn,
         row_number() OVER (PARTITION BY incident_id, retrying_count > 0 ORDER BY sampled_at) AS rn_same,
         max(sampled_at) OVER (PARTITION BY incident_id) AS incident_last
  FROM incident_occurrence WINDOW w AS (PARTITION BY incident_id ORDER BY sampled_at)
), r AS (SELECT * FROM o WHERE retrying_count > 0), s AS (SELECT *, rn - rn_same AS island FROM r)
SELECT incident_id, min(sampled_at) AS spell_start, max(sampled_at) AS spell_end, count(*) AS samples,
       (array_agg(dead_letter_count ORDER BY sampled_at))[1] AS dlq_first,
       (array_agg(dead_letter_count ORDER BY sampled_at DESC))[1] AS dlq_last,
       max(dead_letter_count) - min(dead_letter_count) AS dlq_range,
       bool_or(NOT cycle_complete) AS any_blind, bool_or(truncated) AS any_truncated,
       max(sampled_at) < max(incident_last) AS closed
FROM s GROUP BY incident_id, island ORDER BY incident_id, spell_start;
SQL

run ledger/26-triage-snapshot-spans.csv <<'SQL'
SELECT engine_id, lane, count(*) AS rows, min(sampled_at) AS first_ts, max(sampled_at) AS last_ts,
       min(count) AS min_count, max(count) AS max_count
FROM triage_snapshot GROUP BY engine_id, lane ORDER BY engine_id, lane;
SQL

run ledger/30-incident.csv <<'SQL'
SELECT id, signature_hash, algo_version, exception_class, normalized_message, state, first_seen, last_seen,
       last_total, last_truncated, seen_zero_since_resolve, regression_count, last_regressed_at
FROM incident ORDER BY id;
SQL
run ledger/31-incident-episode.csv <<'SQL'
SELECT id, incident_id, start_state, started_at, peak_total, ended_at, resolved_by, ticket_id
FROM incident_episode ORDER BY incident_id, started_at;
SQL
run ledger/32-incident-occurrence.csv <<'SQL'
SELECT incident_id, sampled_at, total, dead_letter_count, retrying_count, truncated, cycle_complete, fleet
FROM incident_occurrence ORDER BY incident_id, sampled_at;
SQL
run ledger/33-bulk-job.csv <<'SQL'
SELECT id, submitted_by, submitted_at, verb, state, total_items, finished_at, scope_kind, scope_label
FROM bulk_job ORDER BY submitted_at;
SQL

echo
echo "output: $OUT"
if [ "${#failed[@]}" -gt 0 ]; then echo "FAILED: ${failed[*]}" >&2; exit 1; fi
echo "all queries ok — archive the folder and hand it over (docs/OPERATIONS.md, Pilot data pull)"
