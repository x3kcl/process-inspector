package io.inspector.catalog;

import java.util.List;

/** One deployed definition plus the other versions of its key (#483 S2). */
public record DefinitionDetailResponse(
        String engineId,
        String kind,
        String definitionId,
        String key,
        String name,
        int version,
        boolean suspended,
        String deploymentId,
        String category,
        String tenantId,
        String resourceName,
        boolean graphicalNotationDefined,
        List<VersionRef> versions) {

    public record VersionRef(String definitionId, int version, boolean latest) {}
}
