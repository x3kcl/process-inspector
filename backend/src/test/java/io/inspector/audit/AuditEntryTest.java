package io.inspector.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The forwarded-identity derivation used by the X-Forwarded-User send-side (M4-CLOSEOUT §2 / D2d). */
class AuditEntryTest {

    private static AuditEntry entry(String actor, boolean breakGlass) {
        return new AuditEntry(
                UUID.randomUUID(),
                "corr-1",
                actor,
                Instant.parse("2026-07-09T12:00:00Z"),
                "engine-a",
                null,
                "pi-1",
                "retry-job",
                null,
                null,
                null,
                breakGlass);
    }

    @Test
    void forwardedIdentityIsTheBareActorForANormalAction() {
        assertThat(entry("alice", false).forwardedIdentity()).isEqualTo("alice");
    }

    @Test
    void forwardedIdentityNamespacesABreakGlassAction() {
        // Namespaced so an engine-side log can never confuse it with a real OIDC subject.
        assertThat(entry("alice", true).forwardedIdentity()).isEqualTo("break-glass-alice");
    }
}
