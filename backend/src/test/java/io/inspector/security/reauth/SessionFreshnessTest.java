package io.inspector.security.reauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * TS-REAUTH-01 (R-SAFE-07): the bounded-freshness decision for the dangerous set. Within the window
 * no re-auth (no MFA storm on a P1 fan-out); past it, re-auth; an ABSENT auth_time fails closed.
 */
class SessionFreshnessTest {

    private static final Instant NOW = Instant.parse("2026-07-09T12:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(15);

    @Test
    void aFreshAuthenticationWithinTheWindowNeedsNoReauth() {
        Instant authTime = NOW.minus(Duration.ofMinutes(14));
        assertThat(SessionFreshness.requiresReauth(authTime, NOW, WINDOW)).isFalse();
    }

    @Test
    void anAuthenticationOlderThanTheWindowForcesReauth() {
        Instant authTime = NOW.minus(Duration.ofMinutes(16));
        assertThat(SessionFreshness.requiresReauth(authTime, NOW, WINDOW)).isTrue();
    }

    @Test
    void exactlyAtTheWindowIsStillFresh() {
        assertThat(SessionFreshness.requiresReauth(NOW.minus(WINDOW), NOW, WINDOW))
                .isFalse();
    }

    @Test
    void anAbsentAuthTimeFailsClosed() {
        assertThat(SessionFreshness.requiresReauth(null, NOW, WINDOW)).isTrue();
    }
}
