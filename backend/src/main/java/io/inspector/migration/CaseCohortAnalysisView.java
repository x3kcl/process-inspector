package io.inspector.migration;

import java.util.List;
import java.util.UUID;

/**
 * A stored case cohort analysis (#500). The pre-check is the single-case one: every live
 * plan-item definition id of the case must exist in the target case model. A case that fails it
 * is a blocker and is absent from {@code executableIds}.
 */
public record CaseCohortAnalysisView(
        UUID id,
        String engineId,
        String status,
        String sourceCaseDefinitionId,
        String targetCaseDefinitionId,
        CaseCohortRequest filter,
        int blockerCount,
        int cleanCount,
        List<PlanItemAggregate> byPlanItem,
        List<BlockedCase> blockers,
        List<String> executableIds,
        long cohortTotal,
        boolean lowerBound,
        String boundLabel,
        String failure,
        String banner) {

    public static final String BANNER = "Inspector estimate — the engine is the only ground truth at execute";

    /** How many analyzed cases hold a live plan item of a definition the target model lacks. */
    public record PlanItemAggregate(String planItemDefinitionId, int cases) {}

    public record BlockedCase(String caseInstanceId, String reason) {}
}
