package io.inspector.catalog;

import java.util.List;

/**
 * One deployment, the definitions it contains, and a server-fresh running-instance count.
 * {@code runningCountBounded} means the count is a floor (a leg failed or the definition list
 * was capped) — delete refuses in that state rather than guessing the deployment is empty.
 */
public record DeploymentDetailResponse(
        String engineId,
        String deploymentId,
        String name,
        String category,
        String tenantId,
        String deploymentTime,
        long runningCount,
        boolean runningCountBounded,
        List<ContainedDefinition> definitions,
        List<String> resources) {

    public record ContainedDefinition(String definitionId, String key, String name, int version, Long runningCount) {}
}
