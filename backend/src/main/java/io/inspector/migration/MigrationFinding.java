package io.inspector.migration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One typed annotation on the migration pre-flight estimate (INSTANCE-MIGRATION.md §14, issue
 * #349/#355) — the citable replacement for the ad-hoc {@code bffWarnings} string list.
 *
 * <p><b>The governing restraint (§14.0):</b> a finding is never a prediction of the engine's
 * verdict. Every predicate is computed ONLY from data the preview already fetches — the two
 * parsed {@link io.inspector.surgery.BpmnStructure} model reads and the instance's active
 * execution list (P14-A: zero new engine calls) — and the vocabulary is FROZEN at the seven
 * codes below. Eight further candidate checks were considered and DROPPED in §14.5; that list
 * is a ceiling, not a backlog.
 *
 * <p><b>Advisory only (§14.6):</b> no finding — green, amber or red — moves a tier-3 rail. The
 * one refusal the pre-check performs ({@link Severity#BLOCKER_ADVICE}) is
 * document-construction impossibility, not an outcome prediction: with no target id and no
 * operator mapping the BFF has nothing sendable for that token.
 *
 * @param code the frozen vocabulary entry
 * @param severity what the operator should do about it
 * @param activityId the source activity the finding is about, or {@code null} for an
 *     instance-level finding (only {@link Code#BOUNDARY_CLOCK_RESET})
 * @param detail human-readable wording that states what the estimate compared, what the engine
 *     does about it, and what nobody can see over REST (§14.4)
 */
public record MigrationFinding(Code code, Severity severity, String activityId, String detail) {

    /**
     * The findings-vocabulary generation, recorded in every {@code migrate-instance/v2} audit
     * payload (§14.8) so a historical row can be read under the vocabulary that produced it.
     * Bump on ANY vocabulary semantics change — code additions, code renames, <b>and severity
     * reassignments of an existing code</b>.
     */
    public static final int TAXONOMY_VERSION = 1;

    public enum Severity {
        /**
         * Execute refuses 422 until the operator supplies a mapping, ONLY because no wire
         * document can be built (§14.0 P14-D). Never an engine verdict.
         */
        BLOCKER_ADVICE,
        /** Migrates; the operator should look first. Never blocks. */
        WARNING,
        /** A factual consequence of migrating this instance. Never blocks. */
        INFO
    }

    /** The seven proven-computable codes locked by §14.3. Nothing else may be added here without a design change and a {@link #TAXONOMY_VERSION} bump. */
    public enum Code {
        /** A leaf token with no counterpart id and no operator mapping — nothing is sendable. */
        UNMAPPED_ACTIVE_ACTIVITY,
        /** An active scope container is gone from the target; its tokens re-home upward. */
        ACTIVE_SCOPE_REMOVED,
        /** A token maps by id, but a scope on its source nesting path is gone from the target. */
        ACTIVE_IN_REMOVED_SCOPE,
        /** Same id and type, moved between scopes that BOTH still exist in the target. */
        NESTING_PATH_CHANGED,
        /** Same id, different element type — the loud silent-corruption class (decision P0-5). */
        TYPE_CHANGED_SAME_ID,
        /** An active boundary event is gone from the target; its protection disappears silently. */
        BOUNDARY_SUBSCRIPTION_REMOVED,
        /** Boundary events are re-subscribed at migrate; a timer's clock MAY restart. */
        BOUNDARY_CLOCK_RESET
    }

    /** Internal severity predicates — {@code severity} is the wire contract, not these. */
    @JsonIgnore
    public boolean isBlockerAdvice() {
        return severity == Severity.BLOCKER_ADVICE;
    }

    @JsonIgnore
    public boolean isWarning() {
        return severity == Severity.WARNING;
    }

    /** The audit-payload projection (§14.8 {@code bffFindings} entry shape) — a plain map so
     * {@code AuditService}'s redaction and jsonb key-canonicalization walk it like any other
     * nested payload node. */
    public Map<String, Object> toAuditEntry() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code.name());
        map.put("severity", severity.name());
        map.put("activityId", activityId);
        map.put("detail", detail);
        return map;
    }

    /* ------------------------------- the locked wording (§14.4) ------------------------------- */

    /**
     * The shared honesty suffix carried by the two blocker→warning downgrades: they were
     * calibrated live on flowable-rest 6.8.0 AND 7.1.0 only; 6.5–6.7 are not in the harness
     * (§14.2). The residual is stated, never hidden, and the engine's atomic apply-time
     * rejection remains the backstop (re-lock decision 10).
     */
    private static final String CALIBRATION_RESIDUAL = " Accepted without a mapping by the engines we calibrated"
            + " (Flowable 6.8 and 7.1); your engine may still reject at execute — atomically, with its exact"
            + " message shown here.";

    static MigrationFinding unmappedActiveActivity(String activityId) {
        return new MigrationFinding(
                Code.UNMAPPED_ACTIVE_ACTIVITY,
                Severity.BLOCKER_ADVICE,
                activityId,
                "The Inspector cannot build a migration instruction for this activity — there is nothing to send."
                        + " The engine is known to reject documents missing it. Pick a target mapping.");
    }

    static MigrationFinding activeScopeRemoved(String activityId) {
        return new MigrationFinding(
                Code.ACTIVE_SCOPE_REMOVED,
                Severity.WARNING,
                activityId,
                "This subprocess scope holds live state and no activity with id '" + activityId + "' exists in the"
                        + " target version — the enclosing region dissolves and its tokens re-home outward."
                        + CALIBRATION_RESIDUAL
                        + " Scope-local variables and event subscriptions of the removed scope have no target scope"
                        + " — the estimate does not read execution-local variables and cannot know what state is"
                        + " lost.");
    }

    static MigrationFinding activeInRemovedScope(String activityId, String removedScopeId) {
        return new MigrationFinding(
                Code.ACTIVE_IN_REMOVED_SCOPE,
                Severity.WARNING,
                activityId,
                "Keeps its id, but the subprocess scope '" + removedScopeId + "' that contains it in the current"
                        + " version is gone from the target — the position is preserved, the containing state is"
                        + " not." + CALIBRATION_RESIDUAL
                        + " Scope-local variables and event subscriptions of the removed scope have no target scope"
                        + " — the estimate does not read execution-local variables and cannot know what state is"
                        + " lost.");
    }

    static MigrationFinding nestingPathChanged(String activityId, Object sourcePath, Object targetPath) {
        return new MigrationFinding(
                Code.NESTING_PATH_CHANGED,
                Severity.WARNING,
                activityId,
                "Keeps its id and type but moved between subprocess scopes (" + sourcePath + " → " + targetPath
                        + "); every scope it leaves still exists in the target. Auto-map accepts it; variable"
                        + " scoping and event context may differ — the estimate compares nesting structure only,"
                        + " never the variables actually held.");
    }

    static MigrationFinding typeChangedSameId(String activityId, String fromType, String toType) {
        return new MigrationFinding(
                Code.TYPE_CHANGED_SAME_ID,
                Severity.WARNING,
                activityId,
                "Keeps its id but changed type (" + fromType + " → " + toType + "). The engine returns success and"
                        + " the new implementation can execute IMMEDIATELY as part of the migrate call — a"
                        + " synchronous service task in this position ran its instance to completion during the"
                        + " migrate in live calibration. Verify the new behavior is intended NOW, not later."
                        + " Neither the Inspector nor Flowable flags this as an error.");
    }

    static MigrationFinding boundarySubscriptionRemoved(String activityId) {
        return new MigrationFinding(
                Code.BOUNDARY_SUBSCRIPTION_REMOVED,
                Severity.WARNING,
                activityId,
                "This boundary event is subscribed right now and no activity with id '" + activityId + "' exists in"
                        + " the target version — the deadline/compensation protection it provided disappears at"
                        + " migrate, with no error anywhere." + CALIBRATION_RESIDUAL);
    }

    static MigrationFinding boundaryClockReset() {
        return new MigrationFinding(
                Code.BOUNDARY_CLOCK_RESET,
                Severity.INFO,
                null,
                "This instance has live boundary-event subscriptions. Boundary events are re-subscribed at migrate"
                        + " even when the model is unchanged, so a timer's clock MAY restart from the migrate call"
                        + " — live calibration saw a 72h timer with 2 minutes elapsed restart at 72h on Flowable"
                        + " 6.8, while 7.1 preserved the original due date. The estimate cannot know which behavior"
                        + " your engine exhibits, and it does not parse event definitions, so it cannot tell timer"
                        + " boundary events from message or signal ones — this is stated for all of them.");
    }
}
