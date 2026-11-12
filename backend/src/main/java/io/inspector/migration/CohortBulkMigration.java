package io.inspector.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.action.ActionResult;
import io.inspector.action.GuardRefusedException;
import io.inspector.audit.BreakGlassActor;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.migration.MigrationAnalysis.Status;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * The migration side of the bulk verb {@code migrate-instance} (#496, strategy A). A bulk item's
 * {@code jobRef} is the id of the completed cohort analysis it came from. That analysis pins the
 * target definition, the shared mapping document and each instance's {@code activityStateDigest}:
 * the compare-and-set baseline the operator approved. Dispatch is one
 * {@link MigrationService#executeBulkItem} per instance — never by-definition migrate, never
 * {@code batch-migrate} (they cannot filter, INSTANCE-MIGRATION.md §7).
 */
@Service
public class CohortBulkMigration {

    public static final String VERB = MigrationService.MIGRATE_ACTION;

    private final MigrationAnalysisRepository analyses;
    private final MigrationService migration;
    private final ProcessApiClient client;
    private final EngineRegistry registry;
    private final ObjectMapper json;
    private final RbacAuthorizer rbac;

    public CohortBulkMigration(
            MigrationAnalysisRepository analyses,
            MigrationService migration,
            ProcessApiClient client,
            EngineRegistry registry,
            ObjectMapper json,
            RbacAuthorizer rbac) {
        this.analyses = analyses;
        this.migration = migration;
        this.client = client;
        this.registry = registry;
        this.json = json;
        this.rbac = rbac;
    }

    /** A completed analysis, ready to dispatch. Blocker instances are absent from {@code executableIds}. */
    public record Cohort(
            UUID analysisId,
            String engineId,
            String fromDefinitionId,
            String toDefinitionId,
            List<String> executableIds,
            int blockerCount,
            Map<String, String> digests,
            List<MigrationMapping> mappings) {}

    /**
     * Loads an analysis for submit. Refuses one that is still running, failed, partial
     * ({@code lowerBound}: the cohort is wider than one analysis), has no target, or predates
     * the stored digests.
     */
    public Cohort resolve(UUID analysisId) {
        MigrationAnalysis row = analyses.findById(analysisId)
                // A case analysis (#500) is not a process analysis: its ids are case ids.
                .filter(candidate -> !CaseCohortAnalysisService.isCaseAnalysis(candidate))
                .orElseThrow(() -> new GuardRefusedException(
                        HttpStatus.NOT_FOUND, "analysis-not-found", "No migration analysis " + analysisId + "."));
        if (row.getStatus() != Status.COMPLETED) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "analysis-not-completed",
                    "Analysis " + analysisId + " is " + row.getStatus()
                            + ". Only a completed analysis can be migrated. Nothing happened.");
        }
        CohortAnalysisService.Payload payload = payload(row);
        if (payload.lowerBound()) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "analysis-partial",
                    "Analysis " + analysisId + " covers only part of its cohort (" + payload.boundLabel()
                            + "). Narrow the filter and analyze again. Nothing happened.");
        }
        if (row.getTargetDefinitionId() == null) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "analysis-no-target",
                    "Analysis " + analysisId + " resolved no target definition. Nothing happened.");
        }
        List<String> executable = payload.executableIds() == null ? List.of() : payload.executableIds();
        Map<String, String> digests = payload.digests() == null ? Map.of() : payload.digests();
        if (!digests.keySet().containsAll(executable)) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "analysis-stale",
                    "Analysis " + analysisId + " has no compare-and-set digest for every instance."
                            + " Analyze again. Nothing happened.");
        }
        CohortPreviewRequest filter = read(row.getCohortFilter(), CohortPreviewRequest.class);
        return new Cohort(
                row.getId(),
                row.getEngineId(),
                row.getSourceDefinitionId(),
                row.getTargetDefinitionId(),
                List.copyOf(executable),
                payload.blockerCount(),
                digests,
                filter.mappings() == null ? List.of() : filter.mappings());
    }

    /**
     * One bulk item. The digest and source definition come from the analysis, so an instance that
     * moved since then refuses {@code instance-moved-since-preview} and the runner settles it
     * {@code skipped}.
     */
    public ActionResult dispatch(
            String analysisRef,
            String engineId,
            String instanceId,
            String reason,
            String ticketId,
            Authentication auth) {
        Cohort cohort = resolve(parse(analysisRef));
        String digest = cohort.digests().get(instanceId);
        if (!cohort.engineId().equals(engineId) || digest == null) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "not-in-analysis",
                    "Instance " + engineId + ":" + instanceId + " is not in analysis " + analysisRef
                            + ". Nothing happened.");
        }
        MigrationRequest request = new MigrationRequest(
                cohort.toDefinitionId(),
                null,
                cohort.mappings(),
                reason,
                ticketId,
                null,
                cohort.fromDefinitionId(),
                digest);
        // A bulk worker thread has no SecurityContext: thread the break-glass flag explicitly.
        BreakGlassActor.set(rbac.isBreakGlass(auth));
        try {
            return migration.executeBulkItem(engineId, instanceId, request, auth);
        } finally {
            BreakGlassActor.clear();
        }
    }

    /** Verify now (R-SAFE-09): re-reads the instance's definition id. Never re-fires. */
    public record Verification(boolean migrated, String evidence) {}

    public Verification verify(String analysisRef, String engineId, String instanceId) {
        Cohort cohort = resolve(parse(analysisRef));
        Map<String, Object> instance =
                client.getRuntimeProcessInstance(registry.require(engineId), CallPriority.INTERACTIVE, instanceId);
        if (instance == null) {
            return new Verification(false, "instance is no longer running — completed or deleted since; needs L3");
        }
        String definitionId = String.valueOf(instance.get("processDefinitionId"));
        return cohort.toDefinitionId().equals(definitionId)
                ? new Verification(true, "verified: instance is on " + definitionId)
                : new Verification(false, "instance is still on " + definitionId + " — the migrate did not apply");
    }

    /**
     * Read-only, never re-fires: is nothing left to migrate for this instance — it is no longer
     * running, or it is on the analysis target now. This is what releases a hierarchy parent
     * (#497) over a child that was never sent or was skipped, whether that happened before or
     * after the child's analysis ran.
     */
    public boolean nothingLeftToMigrate(String analysisRef, String engineId, String instanceId) {
        Cohort cohort = resolve(parse(analysisRef));
        Map<String, Object> instance =
                client.getRuntimeProcessInstance(registry.require(engineId), CallPriority.INTERACTIVE, instanceId);
        return instance == null || cohort.toDefinitionId().equals(String.valueOf(instance.get("processDefinitionId")));
    }

    private static UUID parse(String analysisRef) {
        try {
            return UUID.fromString(analysisRef);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "analysis-ref-invalid",
                    "A migrate item must reference its analysis id. Nothing happened.");
        }
    }

    private CohortAnalysisService.Payload payload(MigrationAnalysis row) {
        return read(row.getPayload(), CohortAnalysisService.Payload.class);
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("migration analysis could not be read", e);
        }
    }
}
