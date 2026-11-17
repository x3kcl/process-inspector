package io.inspector.migration;

/**
 * Case cohort filter for one source case definition version (#500). The pinned definition id is
 * the scope. {@code businessKeyContains} needs Flowable 7.x: 6.8 ignores {@code businessKeyLike}
 * and returns the unfiltered set (measured), so the BFF refuses it there instead of sending it.
 */
public record CaseCohortRequest(
        String fromCaseDefinitionId,
        String toCaseDefinitionId,
        String businessKey,
        Boolean businessKeyContains,
        String startedAfter,
        String startedBefore) {}
