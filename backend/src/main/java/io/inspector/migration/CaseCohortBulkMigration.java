package io.inspector.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.action.ActionRequest;
import io.inspector.action.ActionResult;
import io.inspector.action.ActionVerb;
import io.inspector.action.CaseMigrationExpectation;
import io.inspector.action.CorrectiveActionService;
import io.inspector.action.GuardRefusedException;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.migration.MigrationAnalysis.Status;
import io.inspector.registry.EngineRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * The migration side of the bulk verb {@code migrate-case} (#500): the case sibling of
 * {@link CohortBulkMigration}. A bulk item's {@code jobRef} is the id of the completed case
 * cohort analysis it came from; that analysis pins the target case definition and each case's
 * compare-and-set baseline. Dispatch is one single-case migrate per case through the
 * {@code migrate-case} rails — never a by-definition route (they cannot filter and are 7.x only).
 */
@Service
public class CaseCohortBulkMigration {

    public static final String VERB = ActionVerb.MIGRATE_CASE.path();

    private final MigrationAnalysisRepository analyses;
    private final CorrectiveActionService actions;
    private final CmmnApiClient cmmn;
    private final EngineRegistry registry;
    private final ObjectMapper json;

    public CaseCohortBulkMigration(
            MigrationAnalysisRepository analyses,
            CorrectiveActionService actions,
            CmmnApiClient cmmn,
            EngineRegistry registry,
            ObjectMapper json) {
        this.analyses = analyses;
        this.actions = actions;
        this.cmmn = cmmn;
        this.registry = registry;
        this.json = json;
    }

    /** A completed case analysis, ready to dispatch. Blocker cases are absent from {@code executableIds}. */
    public record Cohort(
            UUID analysisId,
            String engineId,
            String fromCaseDefinitionId,
            String toCaseDefinitionId,
            List<String> executableIds,
            int blockerCount,
            Map<String, String> digests) {}

    /** Loads a case analysis for submit. Refuses one that is running, failed, partial or digest-less. */
    public Cohort resolve(UUID analysisId) {
        MigrationAnalysis row = CaseCohortAnalysisService.require(analyses, analysisId);
        if (row.getStatus() != Status.COMPLETED) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "analysis-not-completed",
                    "Analysis " + analysisId + " is " + row.getStatus()
                            + ". Only a completed analysis can be migrated. Nothing happened.");
        }
        CaseCohortAnalysisService.Payload payload;
        try {
            payload = json.readValue(row.getPayload(), CaseCohortAnalysisService.Payload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("case migration analysis could not be read", e);
        }
        if (payload.lowerBound()) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "analysis-partial",
                    "Analysis " + analysisId + " covers only part of its cohort (" + payload.boundLabel()
                            + "). Narrow the filter and analyze again. Nothing happened.");
        }
        List<String> executable = payload.executableIds() == null ? List.of() : payload.executableIds();
        Map<String, String> digests = payload.digests() == null ? Map.of() : payload.digests();
        if (row.getTargetDefinitionId() == null || !digests.keySet().containsAll(executable)) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "analysis-stale",
                    "Analysis " + analysisId + " has no target or no compare-and-set baseline for every case."
                            + " Analyze again. Nothing happened.");
        }
        return new Cohort(
                row.getId(),
                row.getEngineId(),
                row.getSourceDefinitionId(),
                row.getTargetDefinitionId(),
                List.copyOf(executable),
                payload.blockerCount(),
                digests);
    }

    /** One bulk item. A case that moved since the analysis refuses and the runner settles it {@code skipped}. */
    public ActionResult dispatch(
            String analysisRef, String engineId, String caseId, String reason, String ticketId, Authentication auth) {
        Cohort cohort = resolve(parse(analysisRef));
        String digest = cohort.digests().get(caseId);
        if (!cohort.engineId().equals(engineId) || digest == null) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "not-in-analysis",
                    "Case " + engineId + ":" + caseId + " is not in analysis " + analysisRef + ". Nothing happened.");
        }
        ActionRequest request = new ActionRequest(
                reason,
                ticketId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                cohort.toCaseDefinitionId());
        return actions.executeCaseMigrationBulkItem(
                engineId, caseId, request, auth, new CaseMigrationExpectation(cohort.fromCaseDefinitionId(), digest));
    }

    /** Verify now (R-SAFE-09): re-reads the case's definition id. Never re-fires. */
    public record Verification(boolean migrated, String evidence) {}

    public Verification verify(String analysisRef, String engineId, String caseId) {
        Cohort cohort = resolve(parse(analysisRef));
        Map<String, Object> running =
                cmmn.getCmmnCaseInstance(registry.require(engineId), CallPriority.INTERACTIVE, caseId);
        if (running == null) {
            return new Verification(false, "case is no longer running — completed or deleted since; needs L3");
        }
        String definitionId = String.valueOf(running.get("caseDefinitionId"));
        return cohort.toCaseDefinitionId().equals(definitionId)
                ? new Verification(true, "verified: case is on " + definitionId)
                : new Verification(false, "case is still on " + definitionId + " — the migrate did not apply");
    }

    private static UUID parse(String analysisRef) {
        try {
            return UUID.fromString(analysisRef);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "analysis-ref-invalid",
                    "A migrate-case item must reference its analysis id. Nothing happened.");
        }
    }
}
