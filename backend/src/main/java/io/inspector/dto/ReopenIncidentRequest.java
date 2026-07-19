package io.inspector.dto;

/**
 * POST /api/incidents/{id}/reopen (R-BAU-10 S3). {@code reason} is mandatory (trimmed, ≥10
 * chars) — a deliberate addition over the design table's body-less row: reopen un-claims a
 * human "we fixed this" attestation, and the audit doctrine (R-AUD-10, the ack/unacknowledge
 * precedent — un-ack requires a reason too) demands the why land in the audit row's reason
 * column. Flagged as a design deviation in INCIDENT-LEDGER.md §6.
 */
public record ReopenIncidentRequest(String reason) {}
