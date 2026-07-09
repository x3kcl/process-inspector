package io.inspector.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OIDC / IdP wiring knobs (IDP-SECURITY.md §4, ADR-003 concretized — R-GOV-06). Kept in its
 * own record under {@code inspector.security.oidc} rather than folded into
 * {@link SecurityProperties} so the dev/basic call sites are untouched; the actual
 * {@code spring.security.oauth2.client} registration lives in {@code application-oidc.yml}
 * (deploy config, iron rule — issuer/client/secret-ref are never API surface).
 *
 * <p><b>Issuer pinning (⚠️ sixth-seat).</b> {@code issuer} is the ONE trusted tenant issuer.
 * A {@code groups} claim is trusted only when the id-token {@code iss} matches it — a
 * same-named group minted by a foreign federated tenant therefore cannot mint
 * {@code ACCESS_ADMIN}/{@code REGISTRY_ADMIN}. Blank disables the belt-and-suspenders check
 * (Spring still validates {@code iss} against the registration's {@code issuer-uri}); a real
 * deployment MUST pin it, and MUST point {@code issuer-uri} at a single tenant, never Entra's
 * multi-tenant {@code common}/{@code organizations} endpoint.
 */
@ConfigurationProperties(prefix = "inspector.security.oidc")
public record OidcProperties(
        /* The single trusted issuer URI; groups from any other issuer resolve to zero. */
        String issuer,
        /*
         * Entra groups-overage handling. false (floor): overage is DETECTED and the login fails
         * LEGIBLY (never a silent zero-group success). true: resolution is delegated to a deployed
         * OverageGroupResolver bean (a permissioned Graph add-on) — absent that bean it still fails
         * legibly. Graph resolution itself is a documented deploy add-on, out of S1 scope.
         */
        boolean resolveOverage) {

    public boolean issuerPinned() {
        return issuer != null && !issuer.isBlank();
    }
}
