package io.inspector.catalog;

import java.util.List;

/**
 * Element diff of two BPMN XML documents (#483 S3). Identity is the element {@code id}.
 * Added and changed elements exist on {@code rightDefinitionId}; removed elements exist only
 * on the left. This is a BFF structural diff, not an engine migration validation.
 */
public record BpmnDiffResponse(
        String engineId,
        String leftDefinitionId,
        String rightDefinitionId,
        List<ElementChange> added,
        List<ElementChange> removed,
        List<ElementChange> changed) {

    public record ElementChange(String id, String type, String name, String detail) {}
}
