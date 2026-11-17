package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The body of POST /runtime/process-instances/{id}/migrate (#720): the target definition and the
 * activity mappings, in the order the engine applies them. The key is {@code activityMappings},
 * NOT {@code activityMigrationMappings} (docs/INSTANCE-MIGRATION.md), and it is sent as
 * {@code []} when there is no mapping.
 */
public record MigrationDocument(String toProcessDefinitionId, List<ActivityMapping> activityMappings) {

    /**
     * One element of {@code activityMappings}. The engine's converter tells the three forms
     * apart by which keys are present, so the unused ones are null here and absent on the wire:
     * one-to-one ({@code fromActivityId}, {@code toActivityId}), many-to-one
     * ({@code fromActivityIds}, {@code toActivityId}), one-to-many ({@code fromActivityId},
     * {@code toActivityIds}).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActivityMapping(
            String fromActivityId, List<String> fromActivityIds, String toActivityId, List<String> toActivityIds) {}
}
