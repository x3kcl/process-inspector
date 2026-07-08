package io.inspector.snapshot;

/**
 * The lanes of the Stage-0 triage snapshot time-series (V5__triage_snapshot.sql). These are
 * the status chips the aggregator already computes ({@code statusCounts}: ACTIVE, SUSPENDED,
 * COMPLETED, FAILED, RETRYING — SPEC §4 Stage 0) plus the out-of-scope (CMMN) dead-letter
 * projection reported per engine ({@code outOfScopeDeadletters}).
 *
 * <p>The names match the aggregation's {@code statusCounts} keys verbatim so a status total
 * maps to a lane by {@link #valueOf(String)}; they are also the {@code triage_snapshot_lane_valid}
 * CHECK values — the enum and the DB constraint are one list, kept in lockstep.
 */
public enum SnapshotLane {
    ACTIVE,
    SUSPENDED,
    COMPLETED,
    FAILED,
    RETRYING,
    /** The CMMN (or otherwise non-BPMN) dead-letters this engine's BPMN join excludes, ~6.8+. */
    OUT_OF_SCOPE_DLQ
}
