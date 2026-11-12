package io.inspector.migration;

import io.inspector.action.GuardRefusedException;
import io.inspector.audit.ProtectedDefinition;
import io.inspector.audit.ProtectedDefinitionRepository;
import io.inspector.audit.ProtectedInstance;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Filtered cohort migration (#482). Strategy A from the 2026-10-22 spike: one
 * {@code POST runtime/process-instances/{id}/migrate} per instance. By-definition
 * {@code /migrate} and {@code /batch-migrate} move every running instance of the source
 * version and ignore {@code processInstanceIds}.
 *
 * <p>Execution is the bulk verb {@code migrate-instance} over a completed analysis (#496,
 * {@code POST /api/bulk/migrate}). This class only counts and lists. {@link #CAP} bounds one
 * analysis; a wider cohort reads {@code truncated} and the bulk door refuses its analysis.
 */
@Service
public class CohortMigrationService {

    static final int CAP = 500;
    static final String STRATEGY = "per-instance";

    private final EngineRegistry registry;
    private final ProcessApiClient processes;
    private final ProtectedDefinitionRepository protectedDefinitions;
    private final ProtectedInstanceRepository protectedInstances;

    public CohortMigrationService(
            EngineRegistry registry,
            ProcessApiClient processes,
            ProtectedDefinitionRepository protectedDefinitions,
            ProtectedInstanceRepository protectedInstances) {
        this.registry = registry;
        this.processes = processes;
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

    /**
     * The instance ids an analysis estimates (#495). Same filter and cap as {@link #preview}.
     * {@code lowerBound} means the engine reported more running instances than this page holds.
     */
    ListedCohort listInstances(String engineId, CohortPreviewRequest request) {
        EngineConfig engine = registry.require(engineId);
        String fromId = required(request != null ? request.fromDefinitionId() : null, "fromDefinitionId");
        FlowablePage page = load(engine, request, fromId);
        long total = page != null ? page.total() : 0;
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> row : page != null ? page.dataOrEmpty() : List.<Map<String, Object>>of()) {
            String id = str(row, "id");
            if (id != null) {
                ids.add(id);
            }
        }
        String target = request.toDefinitionId();
        return new ListedCohort(fromId, target, List.copyOf(ids), total, total > ids.size());
    }

    record ListedCohort(
            String fromDefinitionId, String toDefinitionId, List<String> instanceIds, long total, boolean lowerBound) {}

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
