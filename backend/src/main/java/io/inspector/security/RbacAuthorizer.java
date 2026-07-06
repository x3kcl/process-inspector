package io.inspector.security;

import io.inspector.action.ActionVerb;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * The RBAC gate behind every {@code @PreAuthorize} (bean name {@code rbac}) and the guard
 * layer's role checks. Scoped grants (ARCH §5) are resolved at CHECK time: for OIDC
 * sessions the user's groups go through the hot-reloaded {@link ScopeMappingService} on
 * every decision, so a mid-incident grant applies within the reload TTL without
 * re-login. Dev basic/form sessions carry plain ROLE_* authorities = global scope.
 */
@Component("rbac")
public class RbacAuthorizer {

    private final ScopeMappingService scopeMapping;
    private final SecurityProperties props;
    private final InspectorProperties registry;

    public RbacAuthorizer(ScopeMappingService scopeMapping, SecurityProperties props, InspectorProperties registry) {
        this.scopeMapping = scopeMapping;
        this.props = props;
        this.registry = registry;
    }

    /**
     * SpEL entry for the actions endpoint: may this user run {@code verbPath} on
     * {@code engineId}? Unknown verbs pass — the controller answers 404 for them (a typo
     * must not masquerade as a permission problem).
     */
    public boolean canExecute(Authentication auth, String engineId, String verbPath) {
        return ActionVerb.fromPath(verbPath)
                .map(verb -> hasRoleOn(auth, verb.minRole(), engineId))
                .orElse(true);
    }

    /** SpEL entry for non-verb endpoints (notes, audit): role floor on one engine. */
    public boolean atLeastOn(Authentication auth, String role, String engineId) {
        return hasRoleOn(auth, Role.valueOf(role), engineId);
    }

    /** SpEL entry for cross-engine reads (global operations log): role floor anywhere. */
    public boolean atLeast(Authentication auth, String role) {
        Role floor = Role.valueOf(role);
        return grantsFor(auth).stream().anyMatch(g -> g.role().atLeast(floor));
    }

    /** The guard layer's programmatic check (targets the engine's pinned tenant). */
    public boolean hasRoleOn(Authentication auth, Role floor, String engineId) {
        String tenantId = registry.engines().stream()
                .filter(e -> e.id().equals(engineId))
                .findFirst()
                .map(EngineConfig::tenantId)
                .orElse(null);
        return grantsFor(auth).stream().anyMatch(g -> g.covers(floor, engineId, tenantId));
    }

    /** The acting user's scope set — OIDC groups via the mounted mapping, else ROLE_* = global. */
    public Set<ScopeGrant> grantsFor(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return Set.of();
        }
        if (auth instanceof OAuth2AuthenticationToken oauth && oauth.getPrincipal() instanceof OAuth2User user) {
            List<String> groups = groupsOf(user);
            return scopeMapping.grantsForGroups(groups);
        }
        Set<ScopeGrant> grants = new LinkedHashSet<>();
        for (GrantedAuthority authority : auth.getAuthorities()) {
            Role.fromAuthority(authority.getAuthority()).ifPresent(role -> grants.add(ScopeGrant.global(role)));
        }
        return grants;
    }

    @SuppressWarnings("unchecked")
    private List<String> groupsOf(OAuth2User user) {
        Object claim = user.getAttributes().get(props.groupsClaimOrDefault());
        if (claim instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }
}
