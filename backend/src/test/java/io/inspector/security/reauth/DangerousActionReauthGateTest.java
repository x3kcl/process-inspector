package io.inspector.security.reauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.inspector.dto.ReauthHint;
import io.inspector.security.OidcProperties;
import io.inspector.security.RbacAuthorizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

/**
 * TS-REAUTH-02 (R-SAFE-07, IDP-SECURITY.md §5): the inbound dangerous-set freshness gate — the half
 * that ENFORCES {@code max_age} rather than merely recording it. An OIDC session past the window (or
 * with no {@code auth_time}) is challenged; a within-window OIDC session passes; and every non-OIDC
 * session (dev/basic, break-glass, unauthenticated) is exempt so the dev + no-DB test matrix and the
 * IdP-down break-glass affordance are untouched. Pure — a fixed clock, no {@code Thread.sleep}.
 */
class DangerousActionReauthGateTest {

    private static final Instant NOW = Instant.parse("2026-07-09T12:00:00Z");
    private static final int WINDOW_S = 900; // the 15-min default

    private final DangerousActionReauthGate gate =
            new DangerousActionReauthGate(new OidcProperties(null, false, null), Clock.fixed(NOW, ZoneOffset.UTC));

    /* ---------------- OIDC sessions: the freshness decision ---------------- */

    @Test
    void aWithinWindowOidcSessionNeedsNoReauth() {
        Authentication fresh = oidc(NOW.minus(Duration.ofMinutes(14)));
        assertThat(gate.requiresReauth(fresh)).isFalse();
        assertThatCode(() -> gate.enforce(fresh)).doesNotThrowAnyException();

        ReauthHint hint = gate.hint(fresh);
        assertThat(hint.required()).isFalse();
        assertThat(hint.freshUntil())
                .isEqualTo(NOW.minus(Duration.ofMinutes(14)).plusSeconds(WINDOW_S));
        assertThat(hint.windowSeconds()).isEqualTo(WINDOW_S);
    }

    @Test
    void anOidcSessionPastTheWindowIsChallenged() {
        Authentication stale = oidc(NOW.minus(Duration.ofMinutes(16)));
        assertThat(gate.requiresReauth(stale)).isTrue();
        assertThatThrownBy(() -> gate.enforce(stale))
                .isInstanceOf(ReauthRequiredException.class)
                .satisfies(e -> assertThat(((ReauthRequiredException) e).freshnessWindowSeconds())
                        .isEqualTo(WINDOW_S));
        assertThat(gate.hint(stale).required()).isTrue();
    }

    @Test
    void anOidcSessionWithoutAnAuthTimeFailsClosed() {
        // The IdP asserted no auth_time — we cannot prove freshness, so we demand re-auth (never a
        // silent pass), and there is no freshUntil to offer.
        Authentication noAuthTime = oidc(null);
        assertThat(gate.requiresReauth(noAuthTime)).isTrue();
        ReauthHint hint = gate.hint(noAuthTime);
        assertThat(hint.required()).isTrue();
        assertThat(hint.freshUntil()).isNull();
    }

    /* ---------------- non-OIDC sessions: exempt by design ---------------- */

    @Test
    void aDevBasicSessionIsExemptItReauthenticatesEveryXhr() {
        Authentication basic = new UsernamePasswordAuthenticationToken(
                "operator", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertThat(gate.requiresReauth(basic)).isFalse();
        assertThatCode(() -> gate.enforce(basic)).doesNotThrowAnyException();

        ReauthHint hint = gate.hint(basic);
        assertThat(hint.required()).isFalse();
        assertThat(hint.freshUntil()).isNull(); // exempt → the SPA never interstitials a dev session
    }

    @Test
    void aBreakGlassSessionIsExemptItCannotBounceThroughADownIdp() {
        // Break-glass is a sealed form-login (UsernamePassword), never an OAuth2 token — so it is
        // exempt: forcing it to re-auth through the IdP would defeat the whole IdP-down affordance.
        Authentication breakGlass = new UsernamePasswordAuthenticationToken(
                "break-glass",
                "n/a",
                List.of(
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority(RbacAuthorizer.BREAK_GLASS_AUTHORITY)));
        assertThat(gate.requiresReauth(breakGlass)).isFalse();
    }

    @Test
    void aPlainOauth2NonOidcSessionFailsClosedNotExempt() {
        // Defence-in-depth (Copilot review): an OAuth2 token whose principal is NOT an OidcUser (a plain
        // non-OIDC login) has no id-token auth_time — it must be CHALLENGED (fail closed), never waved
        // through as if it were the dev/basic chain. Exemption keys on the chain, not the principal shape.
        DefaultOAuth2User plain =
                new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")), Map.of("sub", "u-1"), "sub");
        Authentication oauth2 = new OAuth2AuthenticationToken(plain, plain.getAuthorities(), "oidc");
        assertThat(gate.requiresReauth(oauth2)).isTrue();
        assertThat(gate.hint(oauth2).required()).isTrue();
        assertThat(gate.hint(oauth2).freshUntil()).isNull();
    }

    @Test
    void aTestingTokenAndAnUnauthenticatedCallerAreExempt() {
        assertThat(gate.requiresReauth(new TestingAuthenticationToken("t", "n/a", "ROLE_ADMIN")))
                .isFalse();
        assertThat(gate.requiresReauth(null)).isFalse();
        assertThat(gate.hint(null).required()).isFalse();
    }

    /* ---------------- a configured (shorter) window is honoured ---------------- */

    @Test
    void aShorterConfiguredWindowTightensTheChallenge() {
        DangerousActionReauthGate tight = new DangerousActionReauthGate(
                new OidcProperties(null, false, 600), Clock.fixed(NOW, ZoneOffset.UTC)); // 10 min
        Authentication elevenMinutesOld = oidc(NOW.minus(Duration.ofMinutes(11)));
        assertThat(tight.requiresReauth(elevenMinutesOld)).isTrue();
        assertThat(tight.hint(elevenMinutesOld).windowSeconds()).isEqualTo(600);
    }

    private static Authentication oidc(Instant authTime) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "u-1");
        if (authTime != null) {
            claims.put("auth_time", authTime.getEpochSecond());
        }
        OidcIdToken idToken =
                new OidcIdToken("id-tok", NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), claims);
        Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new OAuth2AuthenticationToken(new DefaultOidcUser(authorities, idToken), authorities, "oidc");
    }
}
