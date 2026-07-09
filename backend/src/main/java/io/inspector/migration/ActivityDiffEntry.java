package io.inspector.migration;

/**
 * One currently-active source activity, classified by the BFF static auto-map pre-check
 * (P0 re-lock decision P0-1/P0-2). The diff is scoped to the instance's live activities
 * only — the engine auto-maps by activity-id equality and only requires mappings for
 * token-holding activities — and is deliberately shallow: it compares id, TYPE and
 * NESTING path, never reimplementing the engine's migration rules (which vary 6.5→7.x).
 *
 * @param fromActivityId the active source activity id
 * @param fromType its BPMN element type in the source model
 * @param fromName its display name (may be null)
 * @param status the classification
 * @param toActivityId the target activity it maps to (same id for auto/type/nesting;
 *     the operator's chosen target for MAPPED_BY_OVERRIDE; null for FLAGGED_UNMAPPED)
 * @param toType the target activity's type where a target exists, else null
 * @param detail human-readable, engine-neutral explanation of the classification
 */
public record ActivityDiffEntry(
        String fromActivityId,
        String fromType,
        String fromName,
        Status status,
        String toActivityId,
        String toType,
        String detail) {

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
        NESTING_CHANGED
    }

    /** Advisory statuses map fine but warrant operator attention; they never block execute. */
    public boolean isWarning() {
        return status == Status.TYPE_CHANGED || status == Status.NESTING_CHANGED;
    }

    /** A blocker: execute cannot proceed until every flagged activity has a mapping. */
    public boolean isBlocker() {
        return status == Status.FLAGGED_UNMAPPED;
    }
}
