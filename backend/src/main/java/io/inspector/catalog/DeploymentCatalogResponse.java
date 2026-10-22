package io.inspector.catalog;

import io.inspector.dto.SearchResponse.EngineResult;
import java.util.List;
import java.util.Map;

/** Cross-engine deployment list (#483 S4). Newest deploy time first, then engine id. */
public record DeploymentCatalogResponse(List<CatalogDeployment> deployments, Map<String, EngineResult> perEngine) {

    public record CatalogDeployment(
            String engineId,
            String deploymentId,
            String name,
            String category,
            String tenantId,
            String deploymentTime) {}
}
