package io.inspector.security.reauth;

import io.inspector.security.OidcProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Authorization-request resolver for the {@code oidc} chain (IDP-SECURITY.md §4/§5). Always applies
 * PKCE (S1). On the <b>dangerous-set re-auth</b> path (S5) — when the SPA re-initiates login with a
 * {@code reauth} marker after a 401 challenge — it additionally injects {@code max_age} (the
 * freshness window) + {@code prompt=login}, so the IdP forces a fresh authentication (a new
 * {@code auth_time}) rather than silently returning the stale SSO session. Normal logins carry no
 * {@code max_age}, so there is no per-login MFA storm; the bound applies only when a dangerous verb
 * demanded freshness.
 *
 * <p>Login-time conformance (issue #95): {@code max_age} recorded here is not the same as
 * enforced — the redirect is a request, not a promise, and a nonconforming IdP could silently
 * ignore it and echo back the stale SSO session's old {@code auth_time}. Since {@code max_age} is
 * only ever added when {@link #REAUTH_PARAM} is present, and there is no clean way to thread
 * "was max_age requested" through Spring's cached, per-{@code ClientRegistration}
 * {@code JwtDecoderFactory} down to the specific token response it produced, this stashes a
 * one-shot session marker instead — the SAME {@link jakarta.servlet.http.HttpSession} the redirect
 * started on is guaranteed still current when the callback lands (OAuth2's own state-param CSRF
 * defense depends on that same session continuity). {@link ReauthConformantOidcUserService} reads
 * and consumes it to demand a fresh {@code auth_time} at the token-response boundary — never
 * later, at whatever dangerous verb happens to run next.
 */
public class ReauthAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    /** Query marker the SPA adds to /oauth2/authorization/oidc when replaying a dangerous verb. */
    public static final String REAUTH_PARAM = "reauth";

    /**
     * Session attribute set exactly when {@code max_age} was added to the outbound request —
     * {@link ReauthConformantOidcUserService} demands a fresh {@code auth_time} iff this is set,
     * and always consumes (removes) it on the next token response regardless, so an abandoned
     * re-auth attempt can never leak into gating a later, unrelated normal login.
     */
    public static final String REAUTH_SESSION_MARKER = ReauthAuthorizationRequestResolver.class.getName() + ".REAUTH";

    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final OidcProperties oidc;

    public ReauthAuthorizationRequestResolver(ClientRegistrationRepository repo, OidcProperties oidc) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(repo, "/oauth2/authorization");
        this.delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        this.oidc = oidc;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return maybeForceReauth(delegate.resolve(request), request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return maybeForceReauth(delegate.resolve(request, clientRegistrationId), request);
    }

    private OAuth2AuthorizationRequest maybeForceReauth(OAuth2AuthorizationRequest req, HttpServletRequest request) {
        if (req == null || request.getParameter(REAUTH_PARAM) == null) {
            return req; // normal login — no max_age, no forced re-auth
        }
        Map<String, Object> params = new HashMap<>(req.getAdditionalParameters());
        params.put("max_age", String.valueOf(oidc.freshnessWindowSOrDefault()));
        params.put("prompt", "login");
        request.getSession(true).setAttribute(REAUTH_SESSION_MARKER, Boolean.TRUE);
        return OAuth2AuthorizationRequest.from(req).additionalParameters(params).build();
    }
}
