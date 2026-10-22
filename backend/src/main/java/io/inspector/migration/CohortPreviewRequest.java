package io.inspector.migration;

import java.util.List;

/**
 * Cohort filter for one source definition version (#482 S2). The pinned definition id is the
 * scope — an unfiltered call still only sees that version. {@code businessKeyContains} uses
 * {@code businessKeyLike}; otherwise the key is exact. A variable predicate cannot be combined
 * with a business key or a start range: the runtime query POST honors variables and ignores
 * {@code businessKey} (measured 2026-10-22).
 */
public record CohortPreviewRequest(
        String fromDefinitionId,
        String toDefinitionId,
        String businessKey,
        Boolean businessKeyContains,
        String startedAfter,
        String startedBefore,
        String variableName,
        String variableValue,
        List<MigrationMapping> mappings) {}
