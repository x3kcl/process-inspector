package io.inspector.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

/**
 * TS-OIDC-01 — the single authoritative group source (IDP-SECURITY.md §4). Every trust branch
 * is exercised here at rung 1: issuer pinning, non-array-claim rejection, and Entra-overage
 * detect-and-legibly-fail. A silent zero-group success on a malformed identity is a Sev1
 * (R-TEST-03), so the login-mode branches assert a legible throw, never a quiet empty.
 */
class OidcGroupResolverTest {

    private static final String PINNED = "https://login.microsoftonline.com/tenant-a/v2.0";

    private OidcGroupResolver resolver(String pinnedIssuer, boolean resolveOverage, OverageGroupResolver overage) {
        SecurityProperties security = new SecurityProperties(null, null, null, "groups", null, null, null);
        OidcProperties oidc = new OidcProperties(pinnedIssuer, resolveOverage, null);
        @SuppressWarnings("unchecked")
        ObjectProvider<OverageGroupResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(overage);
        return new OidcGroupResolver(security, oidc, provider);
    }

    @Test
    void arrayGroupsFromThePinnedIssuerAreTrusted() {
        var r = resolver(PINNED, false, null);
        Map<String, Object> claims = Map.of("groups", List.of("flowable-admins", "orders-l1"));
        assertThat(r.resolveForLogin(claims, PINNED, "sub-1")).containsExactly("flowable-admins", "orders-l1");
        assertThat(r.resolveForCheck(claims, PINNED, "sub-1")).containsExactly("flowable-admins", "orders-l1");
    }

    @Test
    void groupsFromANonPinnedIssuerResolveToZero() {
        var r = resolver(PINNED, false, null);
        // A foreign tenant minting the ACCESS_ADMIN group name must not confer the apex grant.
        Map<String, Object> claims = Map.of("groups", List.of("access-admins"));
        String foreign = "https://login.microsoftonline.com/evil-tenant/v2.0";
        assertThat(r.resolveForLogin(claims, foreign, "sub-1")).isEmpty();
        assertThat(r.resolveForCheck(claims, foreign, "sub-1")).isEmpty();
    }

    @Test
    void withoutIssuerPinningAnyIssuerIsTrusted() {
        var r = resolver(null, false, null);
        Map<String, Object> claims = Map.of("groups", List.of("orders-l1"));
        assertThat(r.resolveForLogin(claims, "https://any-issuer/", "sub-1")).containsExactly("orders-l1");
    }

    @Test
    void absentGroupsWithNoOverageIsAnHonestZero() {
        var r = resolver(PINNED, false, null);
        assertThat(r.resolveForLogin(Map.of("sub", "sub-1"), PINNED, "sub-1")).isEmpty();
    }

    @Test
    void nonArrayGroupsClaimFailsLegiblyAtLogin() {
        var r = resolver(PINNED, false, null);
        Map<String, Object> claims = Map.of("groups", "orders-l1,orders-l2"); // Keycloak CSV / scalar
        assertThatThrownBy(() -> r.resolveForLogin(claims, PINNED, "sub-1"))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(e -> assertThat(
                                ((OAuth2AuthenticationException) e).getError().getErrorCode())
                        .isEqualTo("invalid_groups_claim"));
    }

    @Test
    void nonArrayGroupsClaimDegradesToZeroAtCheckTime() {
        var r = resolver(PINNED, false, null);
        Map<String, Object> claims = Map.of("groups", "orders-l1,orders-l2");
        assertThat(r.resolveForCheck(claims, PINNED, "sub-1")).isEmpty();
    }

    @Test
    void entraOverageWithNoResolverFailsLegiblyAtLogin() {
        var r = resolver(PINNED, false, null);
        Map<String, Object> claims = Map.of(
                "sub", "sub-1",
                "_claim_names", Map.of("groups", "src1"),
                "_claim_sources", Map.of("src1", Map.of("endpoint", "https://graph.microsoft.com/...")));
        assertThatThrownBy(() -> r.resolveForLogin(claims, PINNED, "sub-1"))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(e -> assertThat(
                                ((OAuth2AuthenticationException) e).getError().getErrorCode())
                        .isEqualTo("groups_overage"));
    }

    @Test
    void entraOverageResolvedViaGraphAddOnWhenEnabled() {
        OverageGroupResolver graph = (claims, subject) -> List.of("big-group-1", "big-group-2");
        var r = resolver(PINNED, true, graph);
        Map<String, Object> claims = Map.of("sub", "sub-1", "_claim_names", Map.of("groups", "src1"));
        assertThat(r.resolveForLogin(claims, PINNED, "sub-1")).containsExactly("big-group-1", "big-group-2");
    }

    @Test
    void overageResolutionDisabledIgnoresAPresentResolverBean() {
        // The bean exists but resolve-overage is off → floor applies (legible fail), not resolution.
        OverageGroupResolver graph = (claims, subject) -> List.of("should-not-be-used");
        var r = resolver(PINNED, false, graph);
        Map<String, Object> claims = Map.of("sub", "sub-1", "_claim_names", Map.of("groups", "src1"));
        assertThatThrownBy(() -> r.resolveForLogin(claims, PINNED, "sub-1"))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }
}
