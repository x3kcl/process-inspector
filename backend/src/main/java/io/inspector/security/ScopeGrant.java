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
}
