package io.inspector.audit;

/**
 * The normative audit-row lifecycle (SPEC §6, R-SEM-18): {@code PENDING → ok | failed |
 * unknown}. Constant names are intentionally the exact spec/DB literals (the V1__init.sql
 * CHECK constraint) — {@code @Enumerated(STRING)} stores {@code name()} verbatim.
 */
public enum AuditOutcome {
    /** Inserted before the engine call; a PENDING row older than write-ms + grace is swept to unknown. */
    PENDING,
    /** Engine confirmed the mutation. */
    ok,
    /** Nothing happened: refused pre-flight (e.g. CAS conflict) or the engine rejected it. */
    failed,
    /** Dispatched (or possibly dispatched) but unverified — never auto-retried, "Verify now" applies. */
    unknown
}
