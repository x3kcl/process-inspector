package io.inspector.client;

import java.util.List;

/** The body of POST /runtime/process-instances/{id}/migrate (#720). */
public record MigrationDocument(String toProcessDefinitionId, List<ActivityMapping> activityMappings) {

    /** One element of {@code activityMappings}. */
    public record ActivityMapping(
            String fromActivityId, List<String> fromActivityIds, String toActivityId, List<String> toActivityIds) {}
}
