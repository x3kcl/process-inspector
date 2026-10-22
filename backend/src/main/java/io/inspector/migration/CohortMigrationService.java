package io.inspector.migration;

import io.inspector.action.EngineRejectedException;
import io.inspector.action.GuardRefusedException;
import io.inspector.action.OutcomeUnknownException;
import io.inspector.audit.ProtectedDefinition;
import io.inspector.audit.ProtectedDefinitionRepository;
import io.inspector.audit.ProtectedInstance;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.migration.CohortExecuteResponse.CohortItemOutcome;
import io.inspector.registry.EngineRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Filtered cohort migration (#482). Strategy A from the 2026-10-22 spike: one
 * {@code POST runtime/process-instances/{id}/migrate} per instance. By-definition
 * {@code /migrate} and {@code /batch-migrate} move every running instance of the source
 * version and ignore {@code processInstanceIds}.
 *
 * <p>ponytail: synchronous cap {@link #CAP}. A wider cohort is refused, not partially sent.
 * Upgrade path: a bulk job that stores the mapping document and paces the same per-item call.
 */
@Service
public class CohortMigrationService {

    static final int CAP = 50;
    static final String STRATEGY = "per-instance";

    private final EngineRegistry registry;
    private final ProcessApiClient processes;
    private final MigrationService migration;
    private final ProtectedDefinitionRepository protectedDefinitions;
    private final ProtectedInstanceRepository protectedInstances;

    public CohortMigrationService(
            EngineRegistry registry,
            ProcessApiClient processes,
            MigrationService migration,
            ProtectedDefinitionRepository protectedDefinitions,
            ProtectedInstanceRepository protectedInstances) {
        this.registry = registry;
        this.processes = processes;
        this.migration = migration;
        this.protectedDefinitions = protectedDefinitions;
        this.protectedInstances = protectedInstances;
    }

    public CohortPreviewResponse preview(String engineId, CohortPreviewRequest request) {
        EngineConfig engine = registry.require(engineId);
        String fromId = required(request != null ? request.fromDefinitionId() : null, "fromDefinitionId");
        FlowablePage page = load(engine, request, fromId);
        long total = page != null ? page.total() : 0;
        boolean truncated = total > CAP;
        long protectedCount = protectedCount(engineId, definitionKey(engine, fromId), page, total);
        return new CohortPreviewResponse(engineId, fromId, total, truncated, CAP, protectedCount, STRATEGY);
    }

    public CohortExecuteResponse execute(String engineId, CohortExecuteRequest request, Authentication auth) {
        if (request == null || request.cohort() == null) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST, "cohort-required", "The cohort filter is required.");
        }
        EngineConfig engine = registry.require(engineId);
        requireReason(request.reason());
        String fromId = required(request.cohort().fromDefinitionId(), "fromDefinitionId");
        FlowablePage page = load(engine, request.cohort(), fromId);
        long total = page != null ? page.total() : 0;
        if (total > CAP) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "cohort-too-wide",
                    "This cohort has " + total + " running instances. The synchronous door caps at " + CAP
                            + ". Narrow the filter. Nothing was migrated.");
        }
        if (request.expectedCount() == null || request.expectedCount() != total) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "cohort-count-drifted",
                    "The cohort now has " + total + " running instances, not the " + request.expectedCount()
                            + " just previewed. Preview again. Nothing was migrated.");
        }
        if (engine.environment() == EngineEnvironment.PROD
                && !String.valueOf(total).equals(request.confirmToken())) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "confirm-token-mismatch",
                    "Migration on a PROD engine is irreversible: type the instance count (\"" + total + "\").");
        }
        String key = definitionKey(engine, fromId);
        boolean definitionProtected = isDefinitionProtected(engineId, key);
        List<CohortItemOutcome> items = new ArrayList<>();
        for (Map<String, Object> row : page != null ? page.dataOrEmpty() : List.<Map<String, Object>>of()) {
            String instanceId = str(row, "id");
            if (instanceId == null) {
                continue;
            }
            if (definitionProtected || isInstanceProtected(engineId, instanceId)) {
                items.add(new CohortItemOutcome(instanceId, "skipped", "protected"));
                continue;
            }
            items.add(migrateOne(engineId, instanceId, request, auth));
        }
        return new CohortExecuteResponse(engineId, total, items);
    }

    private CohortItemOutcome migrateOne(
            String engineId, String instanceId, CohortExecuteRequest request, Authentication auth) {
        CohortPreviewRequest cohort = request.cohort();
        try {
            MigrationRequest probe = new MigrationRequest(
                    cohort.toDefinitionId(), null, cohort.mappings(), null, null, null, null, null);
            MigrationPreview preview = migration.preview(engineId, instanceId, probe, auth);
            if (!preview.executable()) {
                return new CohortItemOutcome(instanceId, "skipped", "pre-check is not executable");
            }
            MigrationRequest exec = new MigrationRequest(
                    cohort.toDefinitionId(),
                    null,
                    cohort.mappings(),
                    request.reason(),
                    request.ticketId(),
                    null,
                    preview.fromDefinitionId(),
                    preview.activityStateDigest());
            migration.executeFromCohort(engineId, instanceId, exec, auth);
            return new CohortItemOutcome(instanceId, "ok", "migrated");
        } catch (OutcomeUnknownException e) {
            return new CohortItemOutcome(instanceId, "unknown", e.getMessage());
        } catch (EngineRejectedException e) {
            return new CohortItemOutcome(instanceId, "failed", e.getMessage());
        } catch (GuardRefusedException e) {
            return new CohortItemOutcome(instanceId, "skipped", e.getMessage());
        }
    }

    private FlowablePage load(EngineConfig engine, CohortPreviewRequest request, String fromId) {
        String variable = blank(request.variableName());
        boolean variableFilter = variable != null;
        boolean other = blank(request.businessKey()) != null
                || blank(request.startedAfter()) != null
                || blank(request.startedBefore()) != null;
        if (variableFilter && other) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "cohort-filter-conflict",
                    "A variable predicate cannot be combined with a business key or a start range."
                            + " The engine query that honors variables ignores businessKey.");
        }
        if (variableFilter) {
            if (blank(request.variableValue()) == null) {
                throw new GuardRefusedException(
                        HttpStatus.BAD_REQUEST, "variable-value-required", "A variable filter needs a value.");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("processDefinitionId", fromId);
            body.put("size", CAP);
            body.put(
                    "variables",
                    List.of(Map.of(
                            "name", variable, "value", request.variableValue().trim(), "operation", "equals")));
            return processes.queryRuntimeProcessInstances(engine, CallPriority.INTERACTIVE, body);
        }
        String key = blank(request.businessKey());
        boolean contains = Boolean.TRUE.equals(request.businessKeyContains());
        return processes.listRuntimeInstances(
                engine,
                CallPriority.INTERACTIVE,
                fromId,
                contains ? null : key,
                contains && key != null ? "%" + key + "%" : null,
                blank(request.startedAfter()),
                blank(request.startedBefore()),
                CAP);
    }

    /** Definition-key protection counts the whole cohort. Otherwise only the fetched page. */
    private long protectedCount(String engineId, String key, FlowablePage page, long total) {
        if (isDefinitionProtected(engineId, key)) {
            return total;
        }
        long n = 0;
        for (Map<String, Object> row : page != null ? page.dataOrEmpty() : List.<Map<String, Object>>of()) {
            String id = str(row, "id");
            if (id != null && isInstanceProtected(engineId, id)) {
                n++;
            }
        }
        return n;
    }

    private boolean isDefinitionProtected(String engineId, String key) {
        if (key == null) {
            return false;
        }
        try {
            return protectedDefinitions
                    .findById(new ProtectedDefinition.Key(engineId, key))
                    .isPresent();
        } catch (RuntimeException e) {
            throw new GuardRefusedException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "protection-unreadable",
                    "The protection registry could not be read. Nothing was migrated.");
        }
    }

    private boolean isInstanceProtected(String engineId, String instanceId) {
        try {
            return protectedInstances
                    .findById(new ProtectedInstance.Key(engineId, instanceId))
                    .isPresent();
        } catch (RuntimeException e) {
            throw new GuardRefusedException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "protection-unreadable",
                    "The protection registry could not be read. Nothing was migrated.");
        }
    }

    private String definitionKey(EngineConfig engine, String definitionId) {
        Map<String, Object> definition = processes.getProcessDefinition(engine, CallPriority.INTERACTIVE, definitionId);
        if (definition == null || definition.get("key") == null) {
            throw new GuardRefusedException(
                    HttpStatus.NOT_FOUND,
                    "definition-not-found",
                    "Process definition " + definitionId + " does not exist on '" + engine.id() + "'.");
        }
        return String.valueOf(definition.get("key"));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() < 10) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "reason-required",
                    "Cohort migration requires a reason of at least 10 characters.");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new GuardRefusedException(HttpStatus.BAD_REQUEST, "missing-field", field + " is required.");
        }
        return value;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }
}
