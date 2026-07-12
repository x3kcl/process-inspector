package io.inspector.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.inspector.config.InspectorProperties;
import io.inspector.security.mapping.MappingSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/**
 * Membership re-pull on re-auth (issue #95, R-SEM-10): {@link DangerousActionReauthGate}'s own
 * Javadoc claims "membership freshness rides for free on the re-auth — a re-auth login yields a
 * NEW id-token, and RbacAuthorizer resolves groups from the id-token claims on every check." This
 * was previously an ASSERTED, unverified claim (no test anywhere proved it — confirmed by
 * research before writing this). It proves out: {@link RbacAuthorizer} caches nothing keyed by
 * user identity — {@link RbacAuthorizer#grantsFor}/{@link RbacAuthorizer#hasRoleOn} are PURE
 * functions of whatever {@link org.springframework.security.core.Authentication} is CURRENTLY in
 * {@code SecurityContextHolder}. A re-auth round trip mechanically replaces that
 * {@code Authentication} with one wrapping a fresh id-token (Spring's standard {@code
 * oauth2Login()} pipeline, unmodified here) — so the very next check after a re-auth
 * automatically sees whatever groups the FRESH token carries, never a stale set frozen at initial
 * login. Deliberately does NOT touch {@link InspectorAuthoritiesMapper}/{@link RbacAuthorizer}'s
 * "id-token is the ONE authoritative claims source, never the merged {@code getAttributes()}"
 * design (their own comments explain why: Entra puts groups in the id token, not userinfo, and
 * the pinned {@code iss} must be the same token the groups come from) — this test only proves the
 * EXISTING mechanism re-resolves fresh, it does not change what it resolves from.
 */
class RbacAuthorizerOidcFreshnessTest {

    private static final String ENGINE = "probe-dev";
    private static final Instant NOW = Instant.parse("2026-07-09T12:00:00Z");

    @Test
    void replacingTheAuthenticationImmediatelyChangesTheResolvedGrantsNoCachingByIdentity() {
        MappingSource mappingSource = mock(MappingSource.class);
        OidcGroupResolver groupResolver = mock(OidcGroupResolver.class);
        InspectorProperties registry = new InspectorProperties(
                null, null, null, null, List.of(io.inspector.support.TestEngines.engine(ENGINE, "http://x/service")));
        RbacAuthorizer rbac = new RbacAuthorizer(mappingSource, registry, groupResolver);

        // Same principal (same subject "alice"), two DIFFERENT id-tokens — exactly what "session
        // before re-auth" vs "session after re-auth" look like: a brand-new Authentication object
        // replacing SecurityContextHolder's, minted by the same OIDC login pipeline.
        var beforeReauth = oidcSession("alice", Map.of("groups", List.of("team-a")));
        var afterReauth = oidcSession("alice", Map.of("groups", List.of("team-b")));

        when(groupResolver.resolveForCheck(eq(beforeReauth.claims()), eq("https://idp.example"), eq("alice")))
                .thenReturn(List.of("team-a"));
        when(groupResolver.resolveForCheck(eq(afterReauth.claims()), eq("https://idp.example"), eq("alice")))
                .thenReturn(List.of("team-b"));
        when(mappingSource.grantsForGroups(List.of("team-a"))).thenReturn(Set.of(ScopeGrant.global(Role.VIEWER)));
        when(mappingSource.grantsForGroups(List.of("team-b"))).thenReturn(Set.of(ScopeGrant.global(Role.ADMIN)));

        // Before re-auth: VIEWER only.
        assertThat(rbac.hasRoleOn(beforeReauth.auth(), Role.ADMIN, ENGINE)).isFalse();
        assertThat(rbac.hasRoleOn(beforeReauth.auth(), Role.VIEWER, ENGINE)).isTrue();

        // The re-auth round trip has replaced the Authentication (modeled here by simply passing
        // the new one — SecurityContextHolder does the actual replacement in the real filter
        // chain, Spring's own unmodified mechanism). The VERY NEXT check already sees ADMIN.
        assertThat(rbac.hasRoleOn(afterReauth.auth(), Role.ADMIN, ENGINE))
                .as("membership re-pull rides for free on re-auth — no identity-keyed cache anywhere")
                .isTrue();

        // And the stale grant is gone — this isn't additive, it's a full re-resolution.
        assertThat(rbac.grantsFor(afterReauth.auth())).containsExactly(ScopeGrant.global(Role.ADMIN));
    }

    private record OidcSession(OAuth2AuthenticationToken auth, Map<String, Object> claims) {}

    private static OidcSession oidcSession(String subject, Map<String, Object> extraClaims) {
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("sub", subject);
        claims.put("iss", "https://idp.example");
        claims.put("auth_time", NOW.getEpochSecond());
        claims.putAll(extraClaims);
        OidcIdToken idToken = new OidcIdToken(
                "id-tok-" + System.identityHashCode(claims),
                NOW.minus(Duration.ofHours(1)),
                NOW.plus(Duration.ofHours(1)),
                claims);
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_VIEWER"));
        var user = new DefaultOidcUser(authorities, idToken);
        return new OidcSession(new OAuth2AuthenticationToken(user, authorities, "oidc"), claims);
    }

    /** Sanity: a non-OIDC (dev/basic) session still resolves from its own ROLE_* authorities — unaffected. */
    @Test
    void aNonOidcSessionIsUntouchedByAnyOfThis() {
        InspectorProperties registry = new InspectorProperties(
                null, null, null, null, List.of(io.inspector.support.TestEngines.engine(ENGINE, "http://x/service")));
        RbacAuthorizer rbac = new RbacAuthorizer(mock(MappingSource.class), registry, mock(OidcGroupResolver.class));
        var basic = new UsernamePasswordAuthenticationToken(
                "operator", "n/a", List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
        assertThat(rbac.hasRoleOn(basic, Role.OPERATOR, ENGINE)).isTrue();
        assertThat(rbac.hasRoleOn(basic, Role.ADMIN, ENGINE)).isFalse();
    }
}
