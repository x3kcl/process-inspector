package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One process definition as {@code /repository/process-definitions} (list and by id) serialises it
 * — the typed wire shape of the process-definition endpoint family (#474). The engine serialises
 * the BPMN resource as a URL ({@code resource}); {@code resourceName} is kept for a payload that
 * carries the plain name. A field the engine does not serialise is null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
