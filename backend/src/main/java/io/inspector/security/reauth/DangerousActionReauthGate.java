package io.inspector.security.reauth;

import io.inspector.dto.ReauthHint;
import io.inspector.security.OidcProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * The inbound enforcement of the dangerous-set re-auth protocol (IDP-SECURITY.md §5, R-SAFE-07) —
 * the half that turns "{@code max_age} recorded" into "{@code max_age} enforced". The outbound half
 * ({@link ReauthAuthorizationRequestResolver}) makes a re-auth login mint a fresh {@code auth_time};
 * this gate reads that {@code auth_time} off the session principal on the dangerous verbs and refuses
 * — with a 401 {@code reauth-required} challenge — when it is older than the bounded freshness window
 * (or absent, which fails closed via {@link SessionFreshness}).
 *
 * <p><b>Scope (the exemption is deliberate, IDP-SECURITY.md §5).</b> The exemption keys on the
 * <em>chain</em>, not the principal shape: only a token-based OAuth2 session is freshness-tracked. The
 * dev Basic/form chain re-authenticates on <em>every</em> XHR, so it is intrinsically fresh; a
 * break-glass session is the IdP-<em>down</em> affordance and by definition cannot bounce through the
 * IdP to re-authenticate — forcing it to would defeat its purpose. Both are
 * {@code UsernamePasswordAuthenticationToken}s (never {@code OAuth2AuthenticationToken}s), so both are
 * exempt, which also keeps the whole dev + no-DB test matrix unaffected. Conversely EVERY
 * {@code OAuth2AuthenticationToken} is tracked and <b>fails closed</b>: a token whose principal is not
 * an {@link OidcUser} (a plain OAuth2, non-OIDC login — not a shape this app's issuer-pinned OIDC
 * chain mints, but a defence-in-depth floor) has no provable {@code auth_time} and so is challenged,
 * never silently waved through.
 *
 * <p>Membership freshness rides for free on the re-auth: a re-auth login yields a NEW id-token, and
 * {@code RbacAuthorizer} resolves groups from the id-token claims on every check — so the replayed
 * verb runs on the just-re-pulled group set, not a token reached at an earlier check.
 */
@Component
public class DangerousActionReauthGate {

    private final OidcProperties oidc;
    private final Clock clock;

    public DangerousActionReauthGate(OidcProperties oidc, Clock clock) {
        this.oidc = oidc;
        this.clock = clock;
    }

    /**
     * Does this session have to re-authenticate before a dangerous verb? {@code false} for any
     * non-OIDC session (dev/basic, break-glass, unauthenticated) — see the class note; for an OIDC
     * session it is the bounded-freshness decision, with an absent {@code auth_time} failing closed.
     */
    public boolean requiresReauth(Authentication auth) {
        if (!isFreshnessTracked(auth)) {
            return false;
        }
        return SessionFreshness.requiresReauth(authTimeOf(auth), clock.instant(), window());
    }

    /**
     * The pre-condition guard on a dangerous verb: throw the 401-mapped challenge when the session is
     * too stale. Placed BEFORE the reason / typed-token rails so a stale operator re-authenticates at
     * verb intent, never after having typed the confirm token (⚠️ support-lead, IDP-SECURITY.md §5).
     */
    public void enforce(Authentication auth) {
        if (requiresReauth(auth)) {
            throw new ReauthRequiredException(oidc.freshnessWindowSOrDefault());
        }
    }

    /**
     * The {@code /api/me} hint the SPA reads at modal open so it can interstitial <em>before</em> the
     * operator types anything. Mirrors {@link #requiresReauth} exactly (same gate, no drift) and adds
     * the {@code freshUntil} instant so the SPA can avoid re-fetching until the window elapses.
     */
    public ReauthHint hint(Authentication auth) {
        int windowSeconds = oidc.freshnessWindowSOrDefault();
        if (!isFreshnessTracked(auth)) {
            return new ReauthHint(false, null, windowSeconds);
        }
        Instant authTime = authTimeOf(auth);
        boolean required = SessionFreshness.requiresReauth(authTime, clock.instant(), window());
        Instant freshUntil = authTime == null ? null : authTime.plus(window());
        return new ReauthHint(required, freshUntil, windowSeconds);
    }

    private Duration window() {
        return Duration.ofSeconds(oidc.freshnessWindowSOrDefault());
    }

    /** A token-based OAuth2 session — the only chain that carries (and must prove) an {@code auth_time}. */
    private static boolean isFreshnessTracked(Authentication auth) {
        return auth != null && auth.isAuthenticated() && auth instanceof OAuth2AuthenticationToken;
    }

    /**
     * The {@code auth_time} claim as an {@link Instant}, or null (→ fail closed) when it cannot be
     * proven: the IdP did not assert it, or the principal is a plain OAuth2 (non-OIDC) login with no
     * id-token to read it from.
     */
    private static Instant authTimeOf(Authentication auth) {
        Object principal = ((OAuth2AuthenticationToken) auth).getPrincipal();
        return principal instanceof OidcUser user ? user.getAuthenticatedAt() : null;
    }
}
