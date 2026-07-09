package io.inspector.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.stereotype.Component;

/**
 * Maps IdP claims/groups into the inspector's ladder authorities (ROLE_VIEWER /
 * ROLE_RESPONDER / ROLE_OPERATOR / ROLE_ADMIN) at login, via the BFF-owned group→scope
 * mapping. These session authorities are the COARSE grants (UI hints, hasRole floors);
 * the authoritative engine/tenant-scoped decision re-resolves the mapping at check time
 * in {@link RbacAuthorizer} so hot-reloaded grants apply to live sessions.
 *
 * <p>Group extraction is delegated to the single authoritative {@link OidcGroupResolver}
 * (IDP-SECURITY.md §4): issuer pinning, non-array-claim rejection and Entra-overage handling
 * are enforced HERE at login in strict mode, so a malformed identity fails the login legibly
 * (never a silent zero-group success).
 */
@Component
public class InspectorAuthoritiesMapper implements GrantedAuthoritiesMapper {

    private final io.inspector.security.mapping.MappingSource mappingSource;
    private final OidcGroupResolver groupResolver;

    public InspectorAuthoritiesMapper(
            io.inspector.security.mapping.MappingSource mappingSource, OidcGroupResolver groupResolver) {
        this.mappingSource = mappingSource;
        this.groupResolver = groupResolver;
    }

    @Override
    public Collection<? extends GrantedAuthority> mapAuthorities(Collection<? extends GrantedAuthority> authorities) {
        Set<GrantedAuthority> mapped = new LinkedHashSet<>(authorities);
        for (GrantedAuthority authority : authorities) {
            List<String> groups = groupsOf(authority);
            for (Role role : mappingSource.rolesForGroups(groups)) {
                mapped.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
            }
        }
        return mapped;
    }

    private List<String> groupsOf(GrantedAuthority authority) {
        if (authority instanceof OidcUserAuthority oidc) {
            // Single authoritative group source (IDP-SECURITY.md §4): the ID TOKEN. Entra puts groups
            // in the id token, NOT the userinfo endpoint, so preferring userinfo would silently DROP
            // them; and the issuer we pin against is the id-token iss, so the group source and the
            // pinned claim must be the same token. Userinfo/Graph is consulted only for overage
            // resolution (OverageGroupResolver), never as the primary group source.
            var idToken = oidc.getIdToken();
            String issuer = idToken.getIssuer() != null ? idToken.getIssuer().toString() : null;
            return groupResolver.resolveForLogin(idToken.getClaims(), issuer, idToken.getSubject());
        }
        if (authority instanceof OAuth2UserAuthority oauth) {
            Map<String, Object> attrs = oauth.getAttributes();
            String issuer = attrs.get("iss") != null ? String.valueOf(attrs.get("iss")) : null;
            String subject = attrs.get("sub") != null ? String.valueOf(attrs.get("sub")) : null;
            return groupResolver.resolveForLogin(attrs, issuer, subject);
        }
        return List.of();
    }
}
