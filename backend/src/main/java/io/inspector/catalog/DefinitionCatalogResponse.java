package io.inspector.catalog;

import io.inspector.dto.SearchResponse.EngineResult;
import java.util.List;
import java.util.Map;

/**
 * Cross-engine definition catalog (#483 S1). Rows are a total order: key, version descending,
 * engine id. {@code countsBounded} is true when a list or a running-count leg was capped or
 * failed — every count on a bounded response is a floor, never a census.
 */
public record DefinitionCatalogResponse(
        List<CatalogDefinition> definitions, Map<String, EngineResult> perEngine, boolean countsBounded) {

    /**
     * @param kind {@code BPMN} or {@code CMMN}
     * @param runningCount null when that row's count leg failed or was past the cap
     * @param countLowerBound true when {@code runningCount} is a floor (or unknown)
     * @param protectedDefinition tri-state: null means the protection registry could not be read
     */
    public record CatalogDefinition(
            String engineId,
            String kind,
            String definitionId,
            String key,
            String name,
            int version,
            boolean latest,
            boolean suspended,
            String deploymentId,
            String category,
            String tenantId,
            Long runningCount,
            boolean countLowerBound,
            Boolean protectedDefinition) {}
}
