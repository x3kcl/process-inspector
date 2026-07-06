package io.inspector.security;

import java.util.Optional;

/**
 * The layered RBAC ladder (R-SAFE-01, SPEC §2 / ARCH §5): VIEWER (read-only) → RESPONDER
 * (tier-0 verbs + unstick + notes — the L1/L2 runbook tier) → OPERATOR (adds tiers 1–2) →
 * ADMIN (adds tiers 3–4). Layered means a higher role covers every lower one.
 */
public enum Role {
    VIEWER(0),
    RESPONDER(1),
    OPERATOR(2),
    ADMIN(3);

    private final int rank;

    Role(int rank) {
        this.rank = rank;
    }

    public boolean atLeast(Role floor) {
        return rank >= floor.rank;
    }

    /** Parses "ROLE_ADMIN" or "ADMIN"; empty for authorities outside the ladder. */
    public static Optional<Role> fromAuthority(String authority) {
        String name = authority.startsWith("ROLE_") ? authority.substring("ROLE_".length()) : authority;
        try {
            return Optional.of(Role.valueOf(name));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
