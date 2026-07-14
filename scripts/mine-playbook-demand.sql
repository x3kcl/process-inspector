-- R-GOV-08 demand-trigger measurement (issue #106, S0 slice — TEST-STRATEGY/IMPLEMENTATION-PLAN
-- gate: "S0 measurement slice first... the result decides whether the R-GOV-08 demand trigger
-- actually fires"). Read-only against the BFF's own audit_entry store.
--
-- R-GOV-08 text: "audit analysis over >=3 pilot months shows repeated identical >=2-verb
-- sequences on >=10 instances of one signature".
--
-- Methodology (documented so a future re-run can be judged against the same yardstick):
--   - One "sequence" = one instance's FULL ordered list of successful (outcome='ok'),
--     instance-scoped corrective-action verbs, chronological by ts. This is the "what did the
--     operator do, start to finish, to fix this instance" reading of R-GOV-08 — matching the
--     product design (record-from-exemplar: a playbook is recorded from ONE operator's full
--     remediation session, then checked against how often that exact session recurs). A
--     sliding-window/subsequence (n-gram) mining approach was considered and rejected as
--     over-engineering for a yes/no gate check; if the gate ever fires on a near-miss, revisit.
--   - "instance-scoped corrective-action verb" excludes read-only (access-read) and
--     administrative/governance actions (registry-*, view-*, break-glass-login,
--     error-group-acknowledge) — these aren't remediation steps a playbook would replay.
--   - Only sequences with >=2 actions count (R-GOV-08's own floor).
--   - A "signature" = a distinct exact sequence (verb list, order-sensitive). Trigger fires when
--     ANY signature's distinct-instance count is >=10, PROVIDED the underlying data spans
--     >=3 distinct calendar months (else the volume is real but the pilot window hasn't run
--     long enough to trust it isn't a one-off spike).

WITH instance_sequences AS (
    SELECT
        engine_id,
        instance_id,
        array_agg(action ORDER BY ts) AS sequence,
        count(*) AS sequence_length,
        min(ts) AS first_ts,
        max(ts) AS last_ts
    FROM audit_entry
    WHERE instance_id IS NOT NULL
      AND outcome = 'ok'
      AND action NOT IN (
          'access-read', 'registry-add', 'registry-disable', 'registry-enable',
          'registry-probe', 'registry-proposal', 'registry-remove', 'registry-seed',
          'break-glass-login', 'error-group-acknowledge', 'view-publish', 'view-unpublish'
      )
    GROUP BY engine_id, instance_id
    HAVING count(*) >= 2
),
signatures AS (
    SELECT
        sequence,
        count(*) AS instance_count,
        min(first_ts) AS earliest_occurrence,
        max(last_ts) AS latest_occurrence
    FROM instance_sequences
    GROUP BY sequence
),
period AS (
    SELECT
        count(DISTINCT date_trunc('month', ts)) AS distinct_months,
        min(ts) AS earliest_row,
        max(ts) AS latest_row
    FROM audit_entry
)
SELECT
    s.sequence,
    s.instance_count,
    s.earliest_occurrence,
    s.latest_occurrence,
    p.distinct_months AS pilot_period_months,
    (s.instance_count >= 10 AND p.distinct_months >= 3) AS trigger_fires
FROM signatures s
CROSS JOIN period p
ORDER BY s.instance_count DESC, s.sequence
LIMIT 50;
