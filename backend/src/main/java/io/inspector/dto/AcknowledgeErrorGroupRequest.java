package io.inspector.dto;

/**
 * POST /api/triage/error-groups/acknowledge (R-BAU-01). Coordinates only — the BFF
 * resolves the group's live engine × definition slices (and their baseline counts /
 * max failing versions) from its OWN aggregation server-side; the browser never
 * attests a count. {@code expiresAt} is an optional ISO-8601 instant.
 */
public record AcknowledgeErrorGroupRequest(
        String signatureHash, Integer algoVersion, String reason, String ticketId, String expiresAt) {}
