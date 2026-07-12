package io.inspector.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Security knobs (SPEC §2, R-SAFE-12). The group→scope mapping deliberately lives in a
 * separately MOUNTED file, not application.yml: adding an engineer to a scope
 * mid-incident is a file edit applied within {@code reloadTtlS}, no pipeline, no restart.
 */
@ConfigurationProperties(prefix = "inspector.security")
public record SecurityProperties(
        /* Dev-profile in-memory login password — dev/test convenience only, env-overridable. */
        String devPassword,
        /* Mounted group→scope mapping file (YAML). Blank = no OIDC group grants resolve. */
        String scopeMappingFile,
        /* Mapping re-read TTL, spec-capped at 60s. */
        Integer reloadTtlS,
        /* IdP claim carrying the user's groups. */
        String groupsClaim,
        /* OIDC group that confers the fleet REGISTRY_ADMIN grant (v2 Registry CRUD, R-SAFE-13). */
        String registryAdminGroup,
        /*
         * Enforce per-engine/tenant scope on SEARCH/TRIAGE reads (S2, R-SAFE-17). Default off:
         * on the dev (`!oidc`) ladder every session is global-scoped so this is a no-op, and a
         * single-team deploy may want the fleet-wide overview. Set true under `oidc` (done in
         * application-oidc.yml) so a per-engine VIEWER cannot read another engine/tenant's rows
         * by naming it in the request. Reads intersect the caller's grants via
         * {@link ScopeGrant#overlaps} at VIEWER floor; mutations were already scoped.
         */
        Boolean scopeReadsEnforced) {

    public String devPasswordOrDefault() {
        return devPassword != null && !devPassword.isBlank() ? devPassword : "dev";
    }

    public int reloadTtlSOrDefault() {
        int ttl = reloadTtlS != null ? reloadTtlS : 60;
        return Math.min(ttl, 60); // R-SAFE-12: applied within a minute, never longer
    }

    public String groupsClaimOrDefault() {
        return groupsClaim != null && !groupsClaim.isBlank() ? groupsClaim : "groups";
    }

    /** OIDC group name conferring REGISTRY_ADMIN; default {@code registry-admin}. */
    public String registryAdminGroupOrDefault() {
        return registryAdminGroup != null && !registryAdminGroup.isBlank() ? registryAdminGroup : "registry-admin";
    }

    /** Whether search/triage reads are scope-filtered (S2, R-SAFE-17); default off (see field doc). */
    public boolean scopeReadsEnforcedOrDefault() {
        return Boolean.TRUE.equals(scopeReadsEnforced);
    }
}
