package io.inspector.security;

import io.inspector.action.ActionVerb;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.security.mapping.FleetGrant;
import io.inspector.security.mapping.MappingSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
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

    /**
     * The fleet-level registry-admin authority (v2 Registry CRUD, R-SAFE-13). ORTHOGONAL to the
     * VIEWER→ADMIN ladder — you cannot scope "add an engine" to an engine that does not exist yet,
     * so this is a distinct named grant, not a rung. Per-engine ADMIN never confers it; break-glass
     * (R-SAFE-11, which grants ADMIN-global) never confers it — repointing the credential vault is
     * not an IdP-outage affordance.
     */
    public static final String REGISTRY_ADMIN_AUTHORITY = "ROLE_REGISTRY_ADMIN";

    private final MappingSource mappingSource;
    private final InspectorProperties registry;
    private final OidcGroupResolver groupResolver;

    public RbacAuthorizer(MappingSource mappingSource, InspectorProperties registry, OidcGroupResolver groupResolver) {
        this.mappingSource = mappingSource;
        this.registry = registry;
        this.groupResolver = groupResolver;
    }

    /**
     * SpEL entry for the actions endpoint: may this user run {@code verbPath} on
     * {@code engineId}? <b>Fail-closed (IDP-SECURITY.md §3.10):</b> an unknown verb path is
     * DENIED ({@code .orElse(false)}) — a verb wired before its enum entry, a rename, or a
     * sub-path must never execute with no role check (the old {@code .orElse(true)} was a
     * fail-OPEN authorization default). The typo→404 UX is preserved separately by the
     * {@code VerbExistenceInterceptor}, which answers 404 for an unknown verb BEFORE this gate
     * evaluates — so a typo dies as "unknown verb", a known-but-forbidden verb as a clean 403,
     * and the authorization decision itself never defaults to allow.
     */
    public boolean canExecute(Authentication auth, String engineId, String verbPath) {
        return ActionVerb.fromPath(verbPath)
                .map(verb -> hasRoleOn(auth, verb.minRole(), engineId))
                .orElse(false);
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

    /**
     * SpEL entry for the registry-admin surface: does this user hold the fleet-level
     * {@code REGISTRY_ADMIN} grant (R-SAFE-13)? Checked at the door via {@code @PreAuthorize} AND
     * re-checked in the service. Resolved from the dedicated OIDC group in prod, or the
     * {@code ROLE_REGISTRY_ADMIN} authority on a dev session — NEVER from a ladder role, so a
     * per-engine ADMIN (and break-glass, which grants ADMIN-global) is refused.
     */
    public boolean canAdministerRegistry(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        if (auth instanceof OAuth2AuthenticationToken oauth && oauth.getPrincipal() instanceof OAuth2User user) {
            // Resolved through the mapping seam: under file mode this is the config registry-admin
            // group (unchanged); under db mode it is a store fleet-grant row. Groups are issuer-pinned.
            return mappingSource.fleetGrantsForGroups(groupsOf(user)).contains(FleetGrant.REGISTRY_ADMIN);
        }
        return auth.getAuthorities().stream().anyMatch(a -> REGISTRY_ADMIN_AUTHORITY.equals(a.getAuthority()));
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
            return mappingSource.grantsForGroups(groups);
        }
        Set<ScopeGrant> grants = new LinkedHashSet<>();
        for (GrantedAuthority authority : auth.getAuthorities()) {
            Role.fromAuthority(authority.getAuthority()).ifPresent(role -> grants.add(ScopeGrant.global(role)));
        }
        return grants;
    }

    /**
     * Check-time group extraction via the single authoritative {@link OidcGroupResolver}
     * (quiet variant — the login already validated the claim shape, so this never throws, but
     * issuer pinning still applies as defense-in-depth: a token whose {@code iss} is not the
     * pinned tenant resolves zero groups).
     */
    private List<String> groupsOf(OAuth2User user) {
        // Same single authoritative source as login (IDP-SECURITY.md §4): for an OIDC session read
        // the ID TOKEN claims (not the merged getAttributes(), where userinfo could shadow groups),
        // so the group source and the pinned iss are the same token.
        if (user instanceof OidcUser oidc) {
            String issuer = oidc.getIssuer() != null ? oidc.getIssuer().toString() : null;
            return groupResolver.resolveForCheck(oidc.getIdToken().getClaims(), issuer, oidc.getSubject());
        }
        Object iss = user.getAttributes().get("iss");
        return groupResolver.resolveForCheck(
                user.getAttributes(), iss != null ? String.valueOf(iss) : null, user.getName());
    }
}
