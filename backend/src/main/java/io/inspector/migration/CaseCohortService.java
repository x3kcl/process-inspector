package io.inspector.migration;

import io.inspector.action.GuardRefusedException;
import io.inspector.client.CaseMigrationClient;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.cmmn.CmmnCapabilities;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Filtered case cohort (#500): counts and lists the running cases of one case definition
 * version. Strategy is the process one (INSTANCE-MIGRATION.md §7): one single-case migrate per
 * case, because the by-definition routes cannot filter. This class only reads.
 */
@Service
public class CaseCohortService {

    static final int CAP = 500;
    static final String STRATEGY = "per-case";

    private final EngineRegistry registry;
    private final CaseMigrationClient cases;

    public CaseCohortService(EngineRegistry registry, CaseMigrationClient cases) {
        this.registry = registry;
        this.cases = cases;
    }

    public CaseCohortPreviewResponse preview(String engineId, CaseCohortRequest request) {
        ListedCohort listed = listCases(engineId, request);
        return new CaseCohortPreviewResponse(
                engineId, listed.fromCaseDefinitionId(), listed.total(), listed.total() > CAP, CAP, STRATEGY);
    }

    /** The case ids an analysis estimates. {@code lowerBound}: the engine reports more than one page holds. */
    ListedCohort listCases(String engineId, CaseCohortRequest request) {
        EngineConfig engine = registry.require(engineId);
        CmmnCapabilities.requireScopeType(registry, engine);
        String fromId = required(request != null ? request.fromCaseDefinitionId() : null, "fromCaseDefinitionId");
        String key = blank(request.businessKey());
        boolean contains = Boolean.TRUE.equals(request.businessKeyContains()) && key != null;
        if (contains) {
            CaseMigrationGate.require7(
                    registry,
                    engine,
                    "A business-key 'contains' filter on cases",
                    "Flowable 6.x ignores businessKeyLike on the case list and would return every case");
        }
        FlowablePage page = cases.listRunningCases(
                engine,
                CallPriority.INTERACTIVE,
                fromId,
                contains ? null : key,
                contains ? "%" + key + "%" : null,
                blank(request.startedAfter()),
                blank(request.startedBefore()),
                CAP);
        long total = page != null ? page.total() : 0;
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> row : page != null ? page.dataOrEmpty() : List.<Map<String, Object>>of()) {
            if (row.get("id") != null) {
                ids.add(String.valueOf(row.get("id")));
            }
        }
        return new ListedCohort(
                fromId, blank(request.toCaseDefinitionId()), List.copyOf(ids), total, total > ids.size());
    }

    record ListedCohort(
            String fromCaseDefinitionId,
            String toCaseDefinitionId,
            List<String> caseIds,
            long total,
            boolean lowerBound) {}

    static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new GuardRefusedException(HttpStatus.BAD_REQUEST, "missing-field", field + " is required.");
        }
        return value.trim();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
