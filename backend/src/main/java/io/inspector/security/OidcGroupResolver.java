package io.inspector.security;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Component;

/**
 * The single authoritative group source (IDP-SECURITY.md §4, ⚠️ sixth-seat). OIDC delivers
 * identity + coarse group IDs only; the (role, engine, tenant) meaning is BFF-owned. This
 * component owns the ONE place group names are extracted from a token so the two consumers —
 * {@link InspectorAuthoritiesMapper} at login and {@link RbacAuthorizer} at check time — apply
 * identical trust rules:
 *
 * <ul>
 *   <li><b>Issuer pinning:</b> {@code groups} is trusted only when the id-token {@code iss}
 *       matches the pinned {@link OidcProperties#issuer()}. A token from any other issuer/tenant
 *       resolves ZERO groups — a same-named group from a foreign tenant can never mint a fleet
 *       grant.</li>
 *   <li><b>Claim-shape validation:</b> {@code groups} present but not a JSON array (Keycloak
 *       scalar / CSV) is a <b>legible failure</b> at login, never a silent zero.</li>
 *   <li><b>Entra overage:</b> {@code _claim_names}/{@code _claim_sources} in place of an inline
 *       {@code groups} claim is detected; resolved via a deployed {@link OverageGroupResolver}
 *       when {@code resolve-overage} is on, else a <b>legible login failure</b> (the floor).</li>
 * </ul>
 *
 * Two entry points differ only in failure mode: {@link #resolveForLogin} throws a legible
 * {@link OAuth2AuthenticationException} so a malformed identity fails the login loudly;
 * {@link #resolveForCheck} never throws (the login already validated the claim shape) but still
 * enforces issuer pinning, degrading anything unexpected to zero groups.
 */
@Component
public class OidcGroupResolver {

    private static final Logger log = LoggerFactory.getLogger(OidcGroupResolver.class);

    /** Entra emits this alongside {@code _claim_sources} when a claim overflows the token. */
    static final String CLAIM_NAMES = "_claim_names";

    private final SecurityProperties security;
    private final OidcProperties oidc;
    private final ObjectProvider<OverageGroupResolver> overageResolver;

    public OidcGroupResolver(
            SecurityProperties security, OidcProperties oidc, ObjectProvider<OverageGroupResolver> overageResolver) {
        this.security = security;
        this.oidc = oidc;
        this.overageResolver = overageResolver;
    }

    /** Login-time resolution: malformed identity → a legible {@link OAuth2AuthenticationException}. */
    public List<String> resolveForLogin(Map<String, Object> claims, String tokenIssuer, String subject) {
        return resolve(claims, tokenIssuer, subject, true);
    }

    /** Check-time resolution: never throws; issuer pinning still applies, unexpected → zero groups. */
    public List<String> resolveForCheck(Map<String, Object> claims, String tokenIssuer, String subject) {
        return resolve(claims, tokenIssuer, subject, false);
    }

    private List<String> resolve(Map<String, Object> claims, String tokenIssuer, String subject, boolean strict) {
        // Issuer pinning (defense-in-depth over Spring's iss validation): trust groups ONLY from the
        // pinned tenant. A foreign-issuer token resolves zero groups — never a fleet-grant mint.
        if (oidc.issuerPinned() && !oidc.issuer().equals(tokenIssuer)) {
            log.warn(
                    "OIDC token issuer [{}] does not match the pinned issuer [{}] — resolving ZERO groups",
                    tokenIssuer,
                    oidc.issuer());
            return List.of();
        }

        String claimName = security.groupsClaimOrDefault();
        Object claim = claims.get(claimName);

        if (claim == null) {
            if (isOverage(claims, claimName)) {
                return resolveOverage(claims, subject, strict);
            }
            return List.of(); // legitimately a member of no groups — zero scope, fail-closed
        }
        if (claim instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        // Present but not an array (Keycloak scalar / CSV group claim). Never silently coerce to zero.
        String detail = "The identity provider returned a '" + claimName
                + "' claim that is not an array; group resolution cannot proceed. Configure the IdP to emit "
                + "groups as a JSON array (contact an administrator).";
        if (strict) {
            throw legible("invalid_groups_claim", detail);
        }
        log.warn("OIDC '{}' claim is not an array at check time — resolving zero groups", claimName);
        return List.of();
    }

    private boolean isOverage(Map<String, Object> claims, String claimName) {
        Object claimNames = claims.get(CLAIM_NAMES);
        return claimNames instanceof Map<?, ?> names && names.containsKey(claimName);
    }

    private List<String> resolveOverage(Map<String, Object> claims, String subject, boolean strict) {
        OverageGroupResolver resolver = oidc.resolveOverage() ? overageResolver.getIfAvailable() : null;
        if (resolver != null) {
            List<String> resolved = resolver.resolveOverageGroups(claims, subject);
            return resolved != null ? resolved : List.of();
        }
        // Floor: detect + legibly fail. A silent zero-group login for an overage user is a Sev1
        // (a quiet lie about why access was denied — R-TEST-03).
        String detail = "Your identity provider returned more groups than fit in the token, and group "
                + "resolution isn't enabled here — contact an administrator.";
        if (strict) {
            throw legible("groups_overage", detail);
        }
        log.warn("OIDC groups-overage detected at check time with no resolver — resolving zero groups");
        return List.of();
    }

    private static OAuth2AuthenticationException legible(String code, String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(code, description, null), description);
    }
}
