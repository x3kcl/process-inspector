package io.inspector.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
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
 */
@Component
public class InspectorAuthoritiesMapper implements GrantedAuthoritiesMapper {

    private final ScopeMappingService scopeMapping;
    private final SecurityProperties props;

    public InspectorAuthoritiesMapper(ScopeMappingService scopeMapping, SecurityProperties props) {
        this.scopeMapping = scopeMapping;
        this.props = props;
    }

    @Override
    public Collection<? extends GrantedAuthority> mapAuthorities(Collection<? extends GrantedAuthority> authorities) {
        Set<GrantedAuthority> mapped = new LinkedHashSet<>(authorities);
        for (GrantedAuthority authority : authorities) {
            List<String> groups = groupsOf(authority);
            for (Role role : scopeMapping.rolesForGroups(groups)) {
                mapped.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
            }
        }
        return mapped;
    }

    @SuppressWarnings("unchecked")
    private List<String> groupsOf(GrantedAuthority authority) {
        Object claim = null;
        if (authority instanceof OidcUserAuthority oidc) {
            claim = oidc.getUserInfo() != null
                    ? oidc.getUserInfo().getClaim(props.groupsClaimOrDefault())
                    : oidc.getIdToken().getClaim(props.groupsClaimOrDefault());
        } else if (authority instanceof OAuth2UserAuthority oauth) {
            claim = oauth.getAttributes().get(props.groupsClaimOrDefault());
        }
        return claim instanceof List<?> list ? (List<String>) list : List.of();
    }
}
