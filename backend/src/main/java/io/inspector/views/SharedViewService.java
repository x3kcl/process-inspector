package io.inspector.views;

import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import io.inspector.security.ScopeGrant;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Team-published shared views (SPEC §8, SHARED-VIEWS.md, R-SEM-24). Distinct from the per-user
 * {@link ViewStoreService}: these rows are team canon, not owner-keyed prefs. This slice (S2) is the
 * READ path only — publish/unpublish/moderate + the audited governance rails land in S3.
 *
 * <p>Read-visibility is an {@code overlaps()} INTERSECTION over the caller's resolved grants (§4.3),
 * NOT owner-keying and NOT {@code covers()} containment. It is DECLUTTER, not a security boundary —
 * search results are grant-blind, so this only tidies which canon the picker offers.
 */
@Service
public class SharedViewService {

    private final SharedViewRepository repository;
    private final RbacAuthorizer rbac;
    private final EngineRegistry registry;

    public SharedViewService(SharedViewRepository repository, RbacAuthorizer rbac, EngineRegistry registry) {
        this.repository = repository;
        this.rbac = rbac;
        this.registry = registry;
    }

    /** The team canon this caller may SEE — every shared view whose scope overlaps a grant of theirs. */
    public List<SharedView> listVisible(Authentication auth) {
        return filterVisible(rbac.grantsFor(auth), repository.findAllByOrderByCreatedAtDesc());
    }

    /**
     * Pure visibility filter (rung-1 testable with crafted {@link ScopeGrant} sets — the scoped RBAC
     * cases the dev basic-auth ladder can't express, since it only ever mints global grants). A view
     * is visible iff SOME grant overlaps its scope at the VIEWER floor.
     */
    public static List<SharedView> filterVisible(Set<ScopeGrant> grants, List<SharedView> all) {
        return all.stream().filter(view -> isVisible(grants, view)).toList();
    }

    private static boolean isVisible(Set<ScopeGrant> grants, SharedView view) {
        return grants.stream().anyMatch(g -> g.overlaps(Role.VIEWER, view.getScopeEngineId(), view.getScopeTenantId()));
    }

    /**
     * The engine→tenant seam for scope derivation/validation (S3): the registry-pinned tenant, or
     * {@code null} when the engine is unknown/untenanted. Resolves DISABLED engines too so a scope
     * still derives when an engine is temporarily off (dangling-canon honesty is S4, not here).
     */
    public String tenantOf(String engineId) {
        return registry.resolve(engineId).map(e -> e.tenantId()).orElse(null);
    }
}
