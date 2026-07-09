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
 */
public class ReauthAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    /** Query marker the SPA adds to /oauth2/authorization/oidc when replaying a dangerous verb. */
    public static final String REAUTH_PARAM = "reauth";

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
        return OAuth2AuthorizationRequest.from(req).additionalParameters(params).build();
    }
}
