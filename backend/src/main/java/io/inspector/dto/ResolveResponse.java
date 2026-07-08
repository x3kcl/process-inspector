package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.inspector.dto.SearchRequest.InstanceStatus;
import java.util.List;
import java.util.Map;

/**
 * GET /api/resolve — the omnibox contract (SPEC §4, R-SEM-04): "a paste of anything"
 * resolved across engines in the normative order process-instance ID → execution ID →
 * task ID → job ID → business key. The response is ALWAYS a disambiguation list — the UI
 * decides navigation (exactly one match → detail; several → picker; BUSINESS_KEY matches →
 * a pre-filtered search, never an auto-navigate).
 *
 * <p>{@code perEngine} is the honesty envelope for the "resolved against N of M engines"
 * banner: an unreachable engine is an {@code ok=false} entry, never a failed response;
 * zero matches with all engines ok is an explicit "not found on any reachable engine".
 */
public record ResolveResponse(String query, List<ResolveMatch> matches, Map<String, EngineProbe> perEngine) {

    /** What kind of thing the pasted string turned out to be, per R-SEM-04 order. */
    public enum MatchKind {
        PROCESS_INSTANCE,
        EXECUTION,
        TASK,
        JOB,
        BUSINESS_KEY,
        /**
         * A CMMN case instance on a co-deployed case engine sharing this engine's tables — NOT a
         * process instance. Read-only; the omnibox opens its detail page at
         * {@code /case/{engineId}/{caseId}} (Case Inspector Phase 2). It carries no
         * {@code compositeId}/{@code processInstanceId} (a case has no owning pid), so that route is
         * built from the engine id and the matched case id. Only claimed on engines that can
         * discriminate scope (Flowable ≥ 6.8), so a pasted Case id is answered truthfully instead
         * of a false "not found on any reachable engine".
         */
        CMMN_CASE
    }

    /**
     * One resolved hit: every kind maps back to its owning process instance — the
     * disambiguation list renders kind + engine badge + status chip and always lands on
     * the instance route ({@code compositeId} is the deep-link primitive).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResolveMatch(
            MatchKind kind,
            String engineId,
            String processInstanceId,
            String compositeId, // engineId:processInstanceId — the Stage 2 route param
            String matchedId, // the pasted id as the engine knows it (execution/task/job id)
            String businessKey,
            String definitionKey,
            Integer definitionVersion,
            String startTime,
            String endTime,
            InstanceStatusFlags flags,
            InstanceStatus status) {}

    /** Per-engine reachability envelope (R-SEM-12). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EngineProbe(boolean ok, String error) {
        public static EngineProbe reached() {
            return new EngineProbe(true, null);
        }

        public static EngineProbe failed(String error) {
            return new EngineProbe(false, error);
        }
    }
}
