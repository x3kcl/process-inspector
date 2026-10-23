package io.inspector.migration;

import java.util.UUID;

/**
 * Id-only progress signal for a cohort analysis (#495). The existing SSE hub forwards the id;
 * browsers refetch {@code GET /api/migration/analyses/{id}}. Not a second bus.
 */
public record MigrationAnalysisChangedEvent(UUID analysisId) {}
