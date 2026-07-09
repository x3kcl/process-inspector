package io.inspector.security;

import java.util.List;
import java.util.Map;

/**
 * Optional extension seam for Entra's groups-overage (IDP-SECURITY.md §4): when a user is in
 * more groups than fit in the token, Entra omits {@code groups} and emits {@code _claim_names}
 * / {@code _claim_sources} pointing at a Graph {@code getMemberObjects} endpoint. Resolving
 * that requires a permissioned Graph call ({@code GroupMember.Read.All}) with a Graph-audience
 * token — a documented, deploy-config add-on, NOT shipped by S1.
 *
 * <p>When NO bean of this type is present the group resolver's floor applies: overage is
 * detected and the login fails <b>legibly</b>, never a silent zero (a quiet lie about why
 * access was denied is a Sev1, R-TEST-03). A deployment that needs overage support provides
 * an implementation; the resolver calls it only when {@code inspector.security.oidc.resolve-overage}
 * is enabled.
 */
public interface OverageGroupResolver {

    /**
     * Resolve the full group set for an overage user from the Graph source pointers.
     *
     * @param claims        the full id-token / userinfo claim set (carries {@code _claim_sources})
     * @param subject       the stable {@code sub} of the authenticating user
     * @return the resolved group names (never null)
     */
    List<String> resolveOverageGroups(Map<String, Object> claims, String subject);
}
