package io.inspector.migration;

/**
 * Count-only case cohort preview (#500). {@code truncated} means the running set is above the
 * analysis cap, so an analysis of it is a lower bound and the bulk door refuses it.
 *
 * @param strategy always {@code per-case}: one single-case migrate per case. By-definition
 *     migrate and batch-migrate cannot filter and exist on 7.x only
 */
public record CaseCohortPreviewResponse(
        String engineId, String fromCaseDefinitionId, long count, boolean truncated, int cap, String strategy) {}
