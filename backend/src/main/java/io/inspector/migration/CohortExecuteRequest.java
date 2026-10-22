package io.inspector.migration;

/**
 * Executes the cohort the preview just counted. {@code expectedCount} is that count; a drift
 * refuses the whole call. On a prod engine {@code confirmToken} must be the decimal count.
 */
public record CohortExecuteRequest(
        CohortPreviewRequest cohort, String reason, String ticketId, String confirmToken, Long expectedCount) {}
