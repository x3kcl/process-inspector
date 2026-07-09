package io.inspector.audit;

/**
 * Per-engine audit-payload minimization mode (R-AUD-03, DATA-CLASSIFICATION §2). Applied at
 * WRITE time so a minimized value is never stored — stronger than read-gating (a later role
 * escalation or a DB dump cannot recover what was never persisted).
 *
 * <ul>
 *   <li>{@link #FULL} — names + values kept (the secret-name denylist still applies
 *       unconditionally). The deliberate opt-in for engines whose variable content is reviewed
 *       as non-sensitive.
 *   <li>{@link #REDACTED} — the <b>default</b> (minimization by default). Structural/skeleton
 *       keys keep their values (name, scope, valueType, ids); every other leaf value is replaced
 *       with {@link AuditService#REDACTED}. You see <i>that</i> a variable changed and its type,
 *       not the values.
 *   <li>{@link #METADATA_ONLY} — the value-bearing keys are dropped entirely; only the skeleton
 *       coordinates survive.
 * </ul>
 *
 * The wire spelling ({@code metadata-only}) matches the DB CHECK constraint and the YAML config;
 * Spring's relaxed enum binding maps {@code metadata-only} → {@link #METADATA_ONLY} automatically,
 * but the DB text column round-trips through {@link #wire()} / {@link #fromWire(String)}.
 */
public enum AuditPayloadMode {
    FULL("full"),
    REDACTED("redacted"),
    METADATA_ONLY("metadata-only");

    private final String wire;

    AuditPayloadMode(String wire) {
        this.wire = wire;
    }

    /** The persisted / config spelling (kebab-case), matching the {@code audit_payload} CHECK. */
    public String wire() {
        return wire;
    }

    public static AuditPayloadMode fromWire(String value) {
        for (AuditPayloadMode mode : values()) {
            if (mode.wire.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unknown audit-payload mode: " + value);
    }
}
