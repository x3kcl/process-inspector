package io.inspector.migration;

/**
 * Count-only cohort preview. {@code truncated} means the running set is above the execute cap,
 * so execute will refuse until the filter narrows it. {@code protectedCount} is how many of the
 * returned page are protected (definition-key protection counts the whole cohort).
 *
 * @param strategy always {@code per-instance} — by-definition migrate and batch-migrate ignore
 *     instance filters (measured #493)
 */
public record CohortPreviewResponse(
        String engineId,
        String fromDefinitionId,
        long count,
        boolean truncated,
        int cap,
        long protectedCount,
        String strategy) {}
