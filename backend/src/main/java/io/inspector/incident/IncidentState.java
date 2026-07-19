package io.inspector.incident;

/**
 * The incident lifecycle (INCIDENT-LEDGER.md §5, R-BAU-10). {@code OPEN} → (human resolve, S3)
 * → {@code RESOLVED} → (automatic regression gate) → {@code REGRESSED}. Names are the
 * {@code incident.state} CHECK values verbatim — enum and DB constraint are one list. The
 * episode {@code start_state} CHECK admits only {@code OPEN} and {@code REGRESSED} (an episode
 * never starts resolved) — enforced in service logic, backed by the DB CHECK.
 */
public enum IncidentState {
    OPEN,
    RESOLVED,
    REGRESSED
}
