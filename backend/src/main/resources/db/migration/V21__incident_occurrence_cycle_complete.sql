-- ============================================================================
-- V21__incident_occurrence_cycle_complete.sql — persist the blind-cycle marker
-- on the occurrence row itself (INCIDENT-LEDGER.md §3.3 / §5, RETRYING-RISK-LANE.md
-- §3.1, ALARM-COST-MODEL.md §6).
--
-- WHY: AggregationSample.cycleComplete (issue #302 — "every registry engine's
-- envelope came back ok() this pass") was recorded on the SAMPLE and consumed
-- ONLY by the zero-state regression gate; it was never persisted. The occurrence
-- time-series therefore carried a single honesty marker (`truncated`, the
-- scan-cap floor) and NOTHING that says "an engine was unreachable when this row
-- was written". Every downstream consumer of the series read a blind row as a
-- real observation:
--   * arrivalsSince (the attention F factor) turned an engine outage's total
--     drop-and-recover into hundreds of phantom ARRIVALS (SUM(GREATEST(δ,0)));
--   * RetrySpellExtractor read the same outage as a RETRYING spell ENDING with
--     no DLQ growth — i.e. fabricated SELF_HEALED evidence for a class that
--     never healed.
-- A blind row must be as loud as a truncated one: same lane, same discard rules.
--
-- DEFAULT/BACKFILL CHOICE — `false` ("assume unobserved"), deliberately:
--   * We never recorded completeness for legacy rows, so `true` would ASSERT a
--     fact that was never observed — exactly the fabrication this migration
--     exists to stop; the phantom arrivals and the fabricated SELF_HEALED spells
--     already sitting in the pilot series would survive the fix for their whole
--     window (28 days for F, 90 for the lane) and keep compounding.
--   * `false` degrades those rows to the SAFE, already-first-class default:
--     arrivals of 0 and INSUFFICIENT_HISTORY. Both features are built around
--     that being the normal answer (ALARM-COST-MODEL §7: the R1 gate is NOT met
--     and `inspector.triage.attention-ordering` ships false; RETRYING-RISK-LANE
--     §7.2/§8: ZERO unconfounded completed spells exist in the pilot ledger
--     today) — so the discarded evidence is measured at ~nothing while the
--     fabrication risk is live and real.
--   * It is the R-SEM-12 precedent applied unchanged: an unknown honesty marker
--     resolves to "untrustworthy", never to "trusted".
-- The DEFAULT is KEPT (not dropped) after the backfill so the fail-closed value
-- also covers any future insert path that forgets to state completeness; the
-- ledger's own upsert always passes it explicitly.
--
-- Adding a NOT NULL column with a non-volatile DEFAULT is metadata-only on
-- Postgres 11+ (no table rewrite) and cascades from the partitioned parent to
-- every existing child + the DEFAULT catch-all.
--
-- ddl-auto=validate holds (iron rule): io.inspector.incident.IncidentOccurrence
-- gains the matching mapped field in the same change.
-- ============================================================================

ALTER TABLE incident_occurrence
    ADD COLUMN cycle_complete boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN incident_occurrence.cycle_complete IS
    'True only when EVERY registry engine answered ok() on the pass that wrote this row (#302). '
    'False = blind row: the counts may be missing an unreachable engine''s members, so deltas '
    'across it are not arrivals and a retrying-count edge at it is not a spell boundary. '
    'Pre-V21 rows are false by backfill — completeness was never recorded for them.';
