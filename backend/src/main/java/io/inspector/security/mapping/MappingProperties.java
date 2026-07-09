package io.inspector.security.mapping;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Group→scope mapping knobs (IDP-SECURITY.md §3.2/§6/§10). Under {@code inspector.security.mapping}.
 *
 * <ul>
 *   <li>{@code source} — {@code file} (today's mounted-YAML semantics, CRUD off) or {@code db}
 *       (DB-authoritative once seeded). Note this is INDEPENDENT of the Spring {@code db} profile
 *       that activates the DB store beans; a deploy sets both together.</li>
 *   <li>{@code accessAdminGroup} — the env-bootstrap apex grant
 *       ({@code INSPECTOR_ACCESS_ADMIN_GROUP}): the always-available lock-out floor. Overlaid as an
 *       {@code ACCESS_ADMIN} fleet grant in BOTH modes regardless of DB/file state, so a bricked DB
 *       or a stale file seed can never leave the tool with no apex authority. Blank = no env floor
 *       (then the store/file must provide the apex, or boot fails the invariant under {@code oidc}).</li>
 * </ul>
 *
 * <p>The {@code REGISTRY_ADMIN} group stays in {@code SecurityProperties.registryAdminGroup} under
 * the file source (unchanged, no dual source of truth); under {@code db} it is a store row.
 */
@ConfigurationProperties(prefix = "inspector.security.mapping")
public record MappingProperties(String source, String accessAdminGroup) {

    public boolean isDbSource() {
        return "db".equalsIgnoreCase(source);
    }

    /** The env-bootstrap apex group, or null when unset (no floor). */
    public String accessAdminGroupOrNull() {
        return accessAdminGroup != null && !accessAdminGroup.isBlank() ? accessAdminGroup : null;
    }
}
