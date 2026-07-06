package io.inspector.dto;

import java.util.List;

/**
 * Search semantics (spec §3.A): AND between categories, OR within a category.
 * - engineIds / statuses are OR-sets;
 * - the scalar filters + every variable filter are ANDed into the engine query.
 */
public record SearchRequest(
        List<String> engineIds,          // empty/null → all enabled engines
        List<InstanceStatus> statuses,   // empty/null → all statuses
        String processDefinitionKey,
        String businessKey,              // Flowable exact match; use %…% for LIKE semantics v2
        String startedAfter,             // ISO-8601, e.g. 2026-07-01T00:00:00Z
        String startedBefore,
        List<VariableFilter> variables,
        Integer pageSize                 // per-engine cap, clamped by engine maxPageSize
) {

    public enum InstanceStatus { ACTIVE, SUSPENDED, COMPLETED, FAILED }

    /** Maps 1:1 onto Flowable's query-variable JSON: {name, value, operation, type}. */
    public record VariableFilter(String name, Object value, String operation, String type) {}

    public List<InstanceStatus> effectiveStatuses() {
        return (statuses == null || statuses.isEmpty())
                ? List.of(InstanceStatus.values())
                : statuses;
    }
}
