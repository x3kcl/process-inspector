package io.inspector.security.reauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.inspector.security.OidcProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Login-time {@code auth_time} conformance (issue #95, IDP-SECURITY.md §5): the token-response
 * boundary check that closes the gap between "{@code max_age} recorded" (the outbound resolver)
 * and "{@code max_age} enforced" (the existing check-time {@link DangerousActionReauthGate}). A
 * nonconforming IdP that ignores {@code max_age} must fail the LOGIN itself, never silently
 * succeed and surface as a confusing 401 minutes later on an unrelated dangerous verb.
 */
class ReauthConformantOidcUserServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-09T12:00:00Z");
    private static final int WINDOW_S = 900; // the 15-min default

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final OidcProperties oidc = new OidcProperties(null, false, WINDOW_S);

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void aNormalLoginWithNoReauthMarkerNeverChecksAuthTimeAtAll() {
        // No max_age was ever requested for this login — even a wildly stale (or absent) auth_time
        // is fine; the check-time gate, not this one, decides whether a dangerous verb needs freshness.
        OidcUser delegateResult = oidcUser(null);
        var service = service(stub(delegateResult));
        withSession(null); // no marker set at all — the common case, every ordinary login

        assertThatCode(() -> service.loadUser(request())).doesNotThrowAnyException();
    }

    @Test
    void aReauthLoginWithAFreshAuthTimePasses() {
        OidcUser delegateResult = oidcUser(NOW.minus(Duration.ofMinutes(5)));
        var service = service(stub(delegateResult));
        withSession(Boolean.TRUE);

        OidcUser result = service.loadUser(request());
        assertThat(result).isSameAs(delegateResult);
    }

    @Test
    void aReauthLoginWithAStaleAuthTimeFailsTheLoginOutright() {
        // The IdP silently echoed the old SSO session's auth_time instead of honoring max_age —
        // exactly the nonconforming behavior this class exists to catch, at login, not later.
        OidcUser delegateResult = oidcUser(NOW.minus(Duration.ofMinutes(20)));
        var service = service(stub(delegateResult));
        withSession(Boolean.TRUE);

        assertThatThrownBy(() -> service.loadUser(request()))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(e -> assertThat(
                                ((OAuth2AuthenticationException) e).getError().getErrorCode())
                        .isEqualTo("stale_auth_time"));
    }

    @Test
    void aReauthLoginWithNoAuthTimeAtAllFailsClosed() {
        // A nonconforming IdP that omits auth_time entirely, even though max_age was requested —
        // same fail-closed posture as the check-time gate (SessionFreshness: null = reject).
        OidcUser delegateResult = oidcUser(null);
        var service = service(stub(delegateResult));
        withSession(Boolean.TRUE);

        assertThatThrownBy(() -> service.loadUser(request())).isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void theMarkerIsConsumedEvenWhenTheCheckPasses() {
        OidcUser delegateResult = oidcUser(NOW.minus(Duration.ofMinutes(1)));
        var service = service(stub(delegateResult));
        MockHttpServletRequest request = withSession(Boolean.TRUE);

        service.loadUser(request());

        assertThat(request.getSession(false).getAttribute(ReauthAuthorizationRequestResolver.REAUTH_SESSION_MARKER))
                .isNull();
    }

    @Test
    void aStaleReauthMarkerFromAnAbandonedAttemptNeverLeaksIntoTheNextLogin() {
        // First call: reauth was expected, and the login (hypothetically) never completed the
        // freshness check because — model this by asserting the marker doesn't survive regardless
        // of outcome. Second call on the SAME session, no marker re-set: must be treated as a
        // normal login (no auth_time enforcement), proving the one-shot consumption is unconditional.
        OidcUser stale = oidcUser(NOW.minus(Duration.ofMinutes(20)));
        var failingService = service(stub(stale));
        MockHttpServletRequest request = withSession(Boolean.TRUE);
        assertThatThrownBy(() -> failingService.loadUser(request()));
        assertThat(request.getSession(false).getAttribute(ReauthAuthorizationRequestResolver.REAUTH_SESSION_MARKER))
                .as("consumed even on the rejecting path")
                .isNull();

        // A later, unrelated normal login on a session that never re-set the marker must pass
        // regardless of how stale its own auth_time is.
        var laterService = service(stub(oidcUser(NOW.minus(Duration.ofHours(3)))));
        assertThatCode(() -> laterService.loadUser(request())).doesNotThrowAnyException();
    }

    private ReauthConformantOidcUserService service(OAuth2UserService<OidcUserRequest, OidcUser> delegate) {
        return new ReauthConformantOidcUserService(delegate, oidc, clock);
    }

    @SuppressWarnings("unchecked")
    private static OAuth2UserService<OidcUserRequest, OidcUser> stub(OidcUser result) {
        OAuth2UserService<OidcUserRequest, OidcUser> delegate = mock(OAuth2UserService.class);
        when(delegate.loadUser(org.mockito.ArgumentMatchers.any())).thenReturn(result);
        return delegate;
    }

    private static OidcUser oidcUser(Instant authTime) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "u-1");
        if (authTime != null) {
            claims.put("auth_time", authTime.getEpochSecond());
        }
        OidcIdToken idToken =
                new OidcIdToken("id-tok", NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), claims);
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_VIEWER")), idToken);
    }

    /** A minimal, real (unused-by-the-mock) OidcUserRequest — the mocked delegate ignores its content. */
    private static OidcUserRequest request() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("oidc")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .clientId("test-client")
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://idp.example/authorize")
                .tokenUri("https://idp.example/token")
                .build();
        OAuth2AccessToken accessToken =
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "tok", NOW.minus(Duration.ofMinutes(1)), NOW);
        return new OidcUserRequest(registration, accessToken, oidcUser(NOW).getIdToken());
    }

    /** Sets (or clears) the current-request session marker and returns the backing mock request. */
    private MockHttpServletRequest withSession(Boolean marker) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true);
        if (marker != null) {
            request.getSession().setAttribute(ReauthAuthorizationRequestResolver.REAUTH_SESSION_MARKER, marker);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }
}
