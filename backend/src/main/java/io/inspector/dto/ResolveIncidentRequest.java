package io.inspector.dto;

/**
 * POST /api/incidents/{id}/resolve (R-BAU-10 S3, INCIDENT-LEDGER.md §6). {@code reason} is
 * mandatory (trimmed, ≥10 chars — the ack door's validation verbatim); {@code ticketId} is
 * optional free text; {@code alsoAcknowledge} (default false) additionally invokes the EXISTING
 * R-BAU-01 acknowledge flow for the incident's signature as a SECOND, separately-audited action
 * — the explicit opt-in checkbox of the panel review (INCIDENT-LEDGER §0 P3), never implicit.
 */
public record ResolveIncidentRequest(String reason, String ticketId, Boolean alsoAcknowledge) {}
