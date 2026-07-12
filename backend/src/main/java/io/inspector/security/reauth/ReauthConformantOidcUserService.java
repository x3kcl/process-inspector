package io.inspector.security.reauth;

import io.inspector.security.OidcProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Login-time {@code auth_time} conformance (issue #95, IDP-SECURITY.md §5 belt-and-suspenders):
 * {@code max_age} recorded by {@link ReauthAuthorizationRequestResolver} is a REQUEST, not a
 * promise — a nonconforming IdP could silently ignore it and echo back the stale SSO session's
 * old {@code auth_time} (or omit the claim entirely). Until this class, nothing checked that at
 * the token-response boundary; the only enforcement was {@link DangerousActionReauthGate}, which
 * runs later, at whatever dangerous verb happens to execute next — a nonconforming IdP would fail
 * silently at login and only surface as a confusing 401 minutes later, on an unrelated request.
 *
 * <p>Wraps the default {@link OidcUserService} unconditionally (so ordinary logins pay zero extra
 * cost and get zero extra behavior) and additionally: reads-and-clears the ONE-SHOT session marker
 * {@link ReauthAuthorizationRequestResolver#REAUTH_SESSION_MARKER} the resolver stashed on the SAME
 * session the redirect started on (guaranteed still current — OAuth2's own state-param CSRF defense
 * depends on that continuity); if it was set, demands the returned {@link OidcUser#getAuthenticatedAt()}
 * satisfy the SAME freshness window {@link SessionFreshness}/{@link DangerousActionReauthGate} use,
 * failing the login outright (never silently) when it does not. The marker is ALWAYS consumed on
 * the next token response, reauth or not, so an abandoned re-auth attempt can never leak into
 * gating a later, unrelated normal login.
 */
@Component
public class ReauthConformantOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final OidcProperties oidc;
    private final Clock clock;

    @Autowired
    public ReauthConformantOidcUserService(OidcProperties oidc, Clock clock) {
        this(new OidcUserService(), oidc, clock);
    }

    /** Test seam: an injectable delegate so the wrapper's own logic is unit-testable in isolation. */
    ReauthConformantOidcUserService(
            OAuth2UserService<OidcUserRequest, OidcUser> delegate, OidcProperties oidc, Clock clock) {
        this.delegate = delegate;
        this.oidc = oidc;
        this.clock = clock;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser user = delegate.loadUser(userRequest);
        if (reauthWasExpected()) {
            Duration window = Duration.ofSeconds(oidc.freshnessWindowSOrDefault());
            if (SessionFreshness.requiresReauth(user.getAuthenticatedAt(), clock.instant(), window)) {
                throw new OAuth2AuthenticationException(
                        new OAuth2Error(
                                "stale_auth_time",
                                "The identity provider did not honor the requested max_age — auth_time is"
                                        + " missing or older than the freshness window.",
                                null),
                        "stale auth_time on a reauth login");
            }
        }
        return user;
    }

    /** Reads AND clears the marker — a one-shot check, never leaking into the next login attempt. */
    private boolean reauthWasExpected() {
        HttpSession session = currentSession();
        if (session == null) {
            return false;
        }
        Object marker = session.getAttribute(ReauthAuthorizationRequestResolver.REAUTH_SESSION_MARKER);
        session.removeAttribute(ReauthAuthorizationRequestResolver.REAUTH_SESSION_MARKER);
        return Boolean.TRUE.equals(marker);
    }

    private static HttpSession currentSession() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        return request.getSession(false);
    }
}
