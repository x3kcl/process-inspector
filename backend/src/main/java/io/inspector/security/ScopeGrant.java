package io.inspector.security;

/**
 * One scoped grant tuple (ARCH §5): a role valid on one engine/tenant — or {@code "*"}
 * wildcards. Grants are scoped, not global: ADMIN on orders-prod/tenant-A authorizes
 * nothing on another engine or tenant.
 */
public record ScopeGrant(Role role, String engineId, String tenantId) {

    public static final String ANY = "*";

    public static ScopeGrant global(Role role) {
        return new ScopeGrant(role, ANY, ANY);
    }

    /**
     * Does this grant satisfy {@code floor} on the given target? A null/blank target
     * tenant means the engine is not tenant-pinned — only tenant-wildcard grants cover it.
     */
    public boolean covers(Role floor, String targetEngineId, String targetTenantId) {
        if (!role.atLeast(floor)) {
            return false;
        }
        if (!ANY.equals(engineId) && !engineId.equals(targetEngineId)) {
            return false;
        }
        if (ANY.equals(tenantId)) {
            return true;
        }
        return targetTenantId != null && !targetTenantId.isBlank() && tenantId.equals(targetTenantId);
    }

    /**
     * Does this grant OVERLAP a shared-view scope (SHARED-VIEWS.md §4.3, R-SEM-24)? This is the
     * read-visibility predicate — a symmetric INTERSECTION, deliberately NOT {@link #covers} (which
     * is containment). {@code covers} asks "does my grant ⊇ this target?"; a concrete grant can never
     * cover a {@code '*'} scope, so reusing it for the picker would hide global canon from everyone
     * but global-grant holders — the inverse of intent. {@code overlaps} asks "is there any
     * engine/tenant BOTH my grant and this scope touch?", so a per-engine VIEWER sees the global
     * canon, and the tenant-A operator sees tenant-A (and global) canon but not tenant-B's.
     *
     * <p>Both operands use {@code '*'} for the wildcard (the {@code shared_view} scope columns are
     * {@code NOT NULL DEFAULT '*'}; a global grant is {@code '*'/'*'}) — so unlike {@code covers} there
     * is no null-tenant special case here. Read-visibility is DECLUTTER, not a security boundary: the
     * search result set is grant-blind today, so the picker filter only tidies the list — it does not
     * redact a shared view's stored query text.
     */
    public boolean overlaps(Role floor, String scopeEngineId, String scopeTenantId) {
        if (!role.atLeast(floor)) {
            return false;
        }
        if (!ANY.equals(engineId) && !ANY.equals(scopeEngineId) && !engineId.equals(scopeEngineId)) {
            return false;
        }
        return ANY.equals(tenantId) || ANY.equals(scopeTenantId) || tenantId.equals(scopeTenantId);
    }
}
