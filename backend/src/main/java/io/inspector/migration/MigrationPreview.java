package io.inspector.migration;

import java.util.List;
import java.util.Map;

/**
 * The instance-migration pre-check result (P0 re-lock). This is the Inspector's own static
 * comparison of the two process models — NOT a Flowable validation ({@code engineValidated}
 * is always {@code false}; Flowable's REST API exposes no migration validator). It classifies
 * the instance's active activities, carries the EXACT migration document execute would send,
 * and states its own limits in {@code banner}. The engine's authoritative check runs only at
 * execute; a clean pre-check can still be rejected at apply time.
 *
 * @param engineValidated always {@code false} — the constant honesty marker (decision P0-6)
 * @param executable true iff no activity is FLAGGED_UNMAPPED (advisory warnings do not block)
 * @param activities every active activity, classified (auto-mapped / flagged / warning)
 * @param activityStateDigest the token-position fingerprint the operator is approving; execute
 *     echoes it back so a move between preview and execute is refused (decision P0-4)
 * @param restBody the exact migration document execute will POST (server-rebuilt, decision P0-4)
 * @param callActivityChildCount child instances that will NOT be migrated (blast-radius)
 * @param banner the honesty banner — never claims the engine checked this
 */
public record MigrationPreview(
        String engineId,
        String instanceId,
        String fromDefinitionId,
        String fromDefinitionKey,
        int fromVersion,
        String toProcessDefinitionId,
        int toVersion,
        boolean engineValidated,
        boolean executable,
        List<ActivityDiffEntry> activities,
        List<TargetActivity> targetActivities,
        String activityStateDigest,
        int callActivityChildCount,
        String method,
        String enginePath,
        Map<String, Object> restBody,
        String summary,
        String banner) {

    /** A flow node in the TARGET version — the options the mapping dropdown offers for a
     * flagged (unmapped) source activity. */
    public record TargetActivity(String id, String name, String type) {}

    public static final String BANNER =
            "Inspector pre-check — this is not a Flowable validation. Flowable's REST API exposes no migration"
                    + " validator, so the result below is the Inspector's own comparison of the two process models"
                    + " (the id, type and nesting of the tokens live in this instance right now) — not the engine's"
                    + " verdict. It can pass mappings the engine will still reject, and it cannot see runtime effects"
                    + " (a re-subscribed timer's due date resets; a parked job's behavior; parallel-join state). The"
                    + " engine's own check runs only when you execute: if it rejects, nothing moves and its exact"
                    + " message is recorded in the audit.";
}
