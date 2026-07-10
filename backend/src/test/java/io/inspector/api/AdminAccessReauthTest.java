package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.inspector.audit.AuditService;
import io.inspector.security.OidcProperties;
import io.inspector.security.mapping.AccessMappingAdminService;
import io.inspector.security.mapping.MappingSource;
import io.inspector.security.reauth.DangerousActionReauthGate;
import io.inspector.security.reauth.ReauthRequiredException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.web.server.ResponseStatusException;

/**
 * Rung 1 (R-SAFE-07, IDP-SECURITY.md §5): every mapping WRITE — add, remove, and the four-eyes
 * approve (itself a mapping-write-class act) — challenges a stale OAuth2 session FIRST, before the
 * file-pin 409 and before any governance check, so the operator re-authenticates at verb intent and
 * the approver's "∉ affected group" test runs on a fresh membership. The dev basic chain (which
 * re-authenticates every XHR) sails through the gate and hits the next rail instead.
 */
class AdminAccessReauthTest {

    private static final Instant NOW = Instant.parse("2026-07-09T12:00:00Z");

    private final MappingSource mappingSource = mock(MappingSource.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AccessMappingAdminService> filePinned = mock(ObjectProvider.class);

    private final AdminAccessController controller = new AdminAccessController(
            mappingSource,
            filePinned, // getIfAvailable() -> null = mapping-source: file (writes 409)
            mock(AuditService.class),
            new DangerousActionReauthGate(new OidcProperties(null, false, null), Clock.fixed(NOW, ZoneOffset.UTC)));

    private static final AdminAccessController.GrantRequest LADDER_ADD = new AdminAccessController.GrantRequest(
            "ladder", "orders-l1", "OPERATOR", "orders-prod", "t1", null, "grant l2 operator access");

    private static Authentication staleOidc() {
        Map<String, Object> claims = Map.of(
                "sub", "u-1", "auth_time", NOW.minus(Duration.ofMinutes(20)).getEpochSecond());
        OidcIdToken idToken =
                new OidcIdToken("id-tok", NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), claims);
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_ACCESS_ADMIN"));
        return new OAuth2AuthenticationToken(new DefaultOidcUser(authorities, idToken), authorities, "oidc");
    }

    @Test
    void aStaleSessionIsChallengedBeforeTheFilePinRefusal() {
        // The 401 challenge outranks the file-pin 409: identity freshness is decided before the
        // store mode is even consulted (challenge at verb intent, never after composing the grant).
        assertThatThrownBy(() -> controller.add(LADDER_ADD, staleOidc())).isInstanceOf(ReauthRequiredException.class);
        assertThatThrownBy(() -> controller.remove(LADDER_ADD, staleOidc()))
                .isInstanceOf(ReauthRequiredException.class);
    }

    @Test
    void theFourEyesApproveIsItselfReauthGated() {
        assertThatThrownBy(() -> controller.approve(7L, staleOidc())).isInstanceOf(ReauthRequiredException.class);
    }

    @Test
    void aDevBasicSessionPassesTheGateAndHitsTheNextRail() {
        // Exempt chain (re-authenticates every XHR) → the gate lets it through and the FILE-PIN 409
        // answers instead — proving the guard order and that dev workflows are untouched.
        when(filePinned.getIfAvailable()).thenReturn(null);
        Authentication basic = new UsernamePasswordAuthenticationToken(
                "access-admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ACCESS_ADMIN")));
        assertThatThrownBy(() -> controller.add(LADDER_ADD, basic))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }
}
