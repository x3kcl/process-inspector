package io.inspector.client;

/** One process definition as the repository endpoints serialise it (#474). */
public record ProcessDefinitionRecord(
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
