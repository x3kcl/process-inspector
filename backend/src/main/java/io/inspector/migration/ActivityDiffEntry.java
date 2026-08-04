package io.inspector.migration;

import java.util.List;

/**
 * One currently-active source activity, classified by the BFF static auto-map pre-check
 * (P0 re-lock decision P0-1/P0-2). The diff is scoped to the instance's live activities
 * only — the engine auto-maps by activity-id equality and only requires mappings for
 * token-holding activities — and is deliberately shallow: it compares id, TYPE and
 * NESTING path, never reimplementing the engine's migration rules (which vary 6.5→7.x).
 *
 * <p>{@link Status} is the execution-DOCUMENT machinery (what, if anything, the BFF puts in
 * {@code activityMappings}); {@link #findings()} is the typed, citable advisory layer added by
 * INSTANCE-MIGRATION.md §14. Severity is DERIVED from the findings, never from the status —
 * that is what let §14 downgrade the two measured false blockers ({@code ACTIVE_SCOPE_REMOVED},
 * {@code BOUNDARY_SUBSCRIPTION_REMOVED}) without touching a single rail.
 *
 * @param fromActivityId the active source activity id
 * @param fromType its BPMN element type in the source model
 * @param fromName its display name (may be null)
 * @param status the mapping-mechanics classification
 * @param toActivityId the target activity it maps to (same id for auto/type/nesting;
 *     the operator's chosen target for MAPPED_BY_OVERRIDE; null when nothing is sendable)
 * @param toType the target activity's type where a target exists, else null
 * @param detail human-readable, engine-neutral explanation of the classification
 * @param findings the typed pre-flight findings on this activity (§14.3); empty when the
 *     activity maps cleanly
 */
public record ActivityDiffEntry(
        String fromActivityId,
        String fromType,
        String fromName,
        Status status,
        String toActivityId,
        String toType,
        String detail,
        List<MigrationFinding> findings) {

    public ActivityDiffEntry {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public enum Status {
        /** Same id exists in the target with the same type and nesting — engine auto-maps it. */
        AUTO_MAPPED,
        /** No same-id activity in the target and no operator override — a mapping is REQUIRED. */
        FLAGGED_UNMAPPED,
        /** Operator supplied an explicit from→to mapping covering this activity. */
        MAPPED_BY_OVERRIDE,
        /**
         * Same id, DIFFERENT type (e.g. userTask → serviceTask). Auto-map accepts it and the
         * engine returns success, but the token lands on different behavior with no error
         * anywhere — the one silent-corruption path (P0-5). Advisory, loud, never a blocker.
         */
        TYPE_CHANGED,
        /** Same id and type, but moved into/out of a subprocess scope. Advisory warning. */
        NESTING_CHANGED,
        /**
         * An active SCOPE CONTAINER (subProcess/transaction/adHocSubProcess) that is absent from
         * the target and is not a multi-instance root. No mapping is sent: live calibration on
         * Flowable 6.8.0 and 7.1.0 proved the engine dissolves the scope and re-homes the tokens
         * on an empty mapping list (§14.2 [M3]). Advisory, never a blocker.
         */
        SCOPE_REMOVED,
        /**
         * An active BOUNDARY EVENT that is absent from the target. No mapping is sent: the engine
         * accepted this on 6.8.0 and 7.1.0 and silently dropped the subscription and its timer
         * job (§14.2 [M4]). Advisory, never a blocker.
         */
        BOUNDARY_REMOVED
    }

    /**
     * Advisory findings map fine but warrant operator attention; they never block execute.
     * Derived from {@link #findings()} — the typed layer is the single source of severity.
     */
    public boolean isWarning() {
        return findings.stream().anyMatch(MigrationFinding::isWarning);
    }

    /**
     * A blocker: execute cannot proceed until this activity has a mapping. NOT a prediction of
     * the engine's verdict — the BFF simply has nothing sendable for this token (§14.0 P14-D).
     */
    public boolean isBlocker() {
        return findings.stream().anyMatch(MigrationFinding::isBlockerAdvice);
    }
}
