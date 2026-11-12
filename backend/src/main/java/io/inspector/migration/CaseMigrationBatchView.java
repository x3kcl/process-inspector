package io.inspector.migration;

import java.util.List;
import java.util.Map;

/**
 * One engine-side case-migration batch (#500, Flowable 7.x). A by-definition batch answers 200
 * before any case has moved; this read is where the outcome shows. {@code partsByStatus} counts
 * the batch parts (one per case) by the engine's own status string, verbatim.
 *
 * @param notSucceededCaseIds a sample of the cases whose part is not {@code success}
 */
public record CaseMigrationBatchView(
        String batchId,
        String status,
        String createTime,
        String completeTime,
        String fromCaseDefinitionId,
        String toCaseDefinitionId,
        int parts,
        Map<String, Integer> partsByStatus,
        List<String> notSucceededCaseIds) {}
