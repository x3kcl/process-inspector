package io.inspector.cmmn;

import io.inspector.action.GuardRefusedException;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * The two reads behind the case-migration pre-check (#500): a case's live plan-item
 * <em>definition</em> ids and the target case model's plan-item definition ids. One
 * implementation for the single-case door, the cohort analysis and the by-definition door, so
 * an estimate and an execute cannot disagree on what was compared.
 */
public final class CaseMigrationPrecheck {

    /** One page. A wider case is refused rather than migrated on a partial pre-check. */
    static final int PLAN_ITEM_CAP = 500;

    private final CmmnApiClient cmmn;

    public CaseMigrationPrecheck(CmmnApiClient cmmn) {
        this.cmmn = cmmn;
    }

    public List<String> activePlanItemDefinitionIds(EngineConfig engine, String caseId) {
        FlowablePage page = cmmn.listCmmnPlanItemInstances(
                engine, CallPriority.INTERACTIVE, Map.of("caseInstanceId", caseId), 0, PLAN_ITEM_CAP);
        List<Map<String, Object>> rows = page == null ? List.of() : page.dataOrEmpty();
        if (page != null && page.total() > rows.size()) {
            throw new GuardRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "plan-items-truncated",
                    "Case " + caseId + " has more plan items than the pre-check read (" + rows.size() + " of "
                            + page.total() + "). Nothing happened.");
        }
        return CasePlanDefinitionIds.activeDefinitionIds(rows);
    }

    /** Plan-item definition ids of a deployed case model, read from its CMMN resource. */
    public Set<String> planDefinitionIds(EngineConfig engine, String caseDefinitionId) {
        return planDefinitionIds(engine, caseDefinitionId, definition(engine, caseDefinitionId));
    }

    /** Same, for a definition row the caller already holds. */
    public Set<String> planDefinitionIds(EngineConfig engine, String caseDefinitionId, Map<String, Object> definition) {
        Object deploymentId = definition.get("deploymentId");
        // The real row has no resourceName, only a resource URL (measured 6.8.0 and 7.1.0).
        String resourceName = CaseDetailService.resourceNameOf(definition);
        if (deploymentId == null || resourceName == null) {
            throw new GuardRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "target-model-unavailable",
                    "Case definition " + caseDefinitionId + " has no CMMN resource to pre-check. Nothing happened.");
        }
        String xml = cmmn.cmmnDeploymentResourceData(
                engine, CallPriority.INTERACTIVE, String.valueOf(deploymentId), resourceName);
        if (xml == null || xml.isBlank()) {
            throw new GuardRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "target-model-unavailable",
                    "Case definition " + caseDefinitionId + " returned no CMMN model. Nothing happened.");
        }
        return CasePlanDefinitionIds.definitionIds(xml);
    }

    /** The deployed case definition row, or a {@code target-not-deployed} refusal. */
    public Map<String, Object> definition(EngineConfig engine, String caseDefinitionId) {
        Map<String, Object> definition = cmmn.getCmmnCaseDefinition(engine, CallPriority.INTERACTIVE, caseDefinitionId);
        if (definition == null) {
            throw new GuardRefusedException(
                    HttpStatus.NOT_FOUND,
                    "target-not-deployed",
                    "Case definition " + caseDefinitionId + " is not deployed. Nothing happened.");
        }
        return definition;
    }
}
