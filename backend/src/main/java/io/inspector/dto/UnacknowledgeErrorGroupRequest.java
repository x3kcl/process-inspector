package io.inspector.dto;

/**
 * POST /api/triage/error-groups/unacknowledge (R-BAU-01). Same rails as acknowledge:
 * un-muting a class the whole shift relies on is a moderation act — the reason (≥10)
 * is mandatory for every caller and lands in the audit row's reason column.
 */
public record UnacknowledgeErrorGroupRequest(String signatureHash, Integer algoVersion, String reason) {}
