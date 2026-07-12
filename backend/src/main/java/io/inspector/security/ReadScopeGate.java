package io.inspector.security;

import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineRegistry;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * The read-side scope gate (S2, R-SAFE-17). Mutations and single-instance detail reads were
 * already scope-checked (per-engine {@code @PreAuthorize VIEWER}); the two FAN-OUT read
 * aggregators — {@code SearchService} and the triage dashboard — enumerated every enabled
 * engine unconditionally, so a per-engine VIEWER could read another engine/tenant's failing
 * instances, exception text and business keys by naming that engine in the request.
 *
 * <p>This gate resolves the set of engine ids the caller may READ (VIEWER floor) by
 * intersecting the caller's {@link ScopeGrant}s against the registry via
 * {@link ScopeGrant#overlaps} — the symmetric read-visibility predicate, deliberately NOT
 * {@code covers} (a concrete per-engine grant overlaps the global canon but does not contain
 * it). It is a per-deploy behaviour behind {@code inspector.security.scope-reads-enforced}:
 * OFF (default, and always effectively so on the dev {@code !oidc} ladder where every session
 * is global-scoped) it returns {@code null} = "unrestricted, do not filter"; ON it returns the
 * concrete readable-id set. Callers treat {@code null} as the fleet-wide legacy behaviour so
 * the enforcement is a pure additive narrowing.
 */
@Component
public class ReadScopeGate {

    private final RbacAuthorizer rbac;
    private final EngineRegistry registry;
    private final SecurityProperties security;

    public ReadScopeGate(RbacAuthorizer rbac, EngineRegistry registry, SecurityProperties security) {
        this.rbac = rbac;
        this.registry = registry;
        this.security = security;
    }

    /** Is read-scope enforcement active for this deploy? */
    public boolean enforced() {
        return security.scopeReadsEnforcedOrDefault();
    }

    /**
     * The enabled-engine ids the caller may read, or {@code null} when enforcement is off (=
     * unrestricted; callers must treat null as "no filter", never as "empty set"). A global grant
     * (every dev session, or a {@code '*'/'*'} OIDC grant) overlaps every engine, so the result is
     * every enabled engine and the narrowing is a no-op — enforcement only bites a genuinely scoped
     * session. Resolved against {@link EngineRegistry#all()} (enabled engines — the fan-out surface),
     * each engine carrying its registry-pinned tenant.
     */
    public Set<String> readableEngineIds(Authentication auth) {
        if (!enforced()) {
            return null;
        }
        Set<ScopeGrant> grants = rbac.grantsFor(auth);
        return registry.all().stream()
                .filter(e -> grants.stream().anyMatch(g -> g.overlaps(Role.VIEWER, e.id(), e.tenantId())))
                .map(EngineConfig::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
