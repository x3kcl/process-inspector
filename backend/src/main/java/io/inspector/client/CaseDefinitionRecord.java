package io.inspector.client;

/** One CMMN case definition (#474). */
public record CaseDefinitionRecord(
        String id,
        String key,
        String name,
        Integer version,
        Boolean suspended,
        String deploymentId,
        String category,
        String tenantId,
        String resource,
        String resourceName,
        Boolean graphicalNotationDefined) {}
