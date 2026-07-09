package io.inspector.security.mapping;

import io.inspector.security.Role;
import io.inspector.security.ScopeGrant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The group→scope mapping seam (IDP-SECURITY.md §6, ⚠️ lead-dev). Fronts the two group-keyed
 * resolution methods the enforcement path already relied on AND adds whole-mapping enumeration
 * + fleet-grant resolution, so access-review, the widen check and drift work under
 * {@code mapping-source: file} too (never silently empty). Two implementations select by profile:
 * {@link FileMappingSource} (default, {@code !db} — today's mounted-YAML semantics, so the large
 * rung-3 suite keeps the file source with zero new mocks) and {@code DbMappingSource}
 * ({@code @Profile("db")} — DB-authoritative once seeded). Every consumer re-reads live, exactly
 * as the file source did, so a committed grant applies within the cache TTL.
 */
public interface MappingSource {

    /** Ladder grants a user's groups carry (check-time scoped decision). */
    Set<ScopeGrant> grantsForGroups(Collection<String> groups);

    /** Distinct ladder roles the groups grant anywhere — the coarse ROLE_* login authorities. */
    Set<Role> rolesForGroups(Collection<String> groups);

    /** Fleet grants a user's groups carry (REGISTRY_ADMIN / ACCESS_ADMIN). */
    Set<FleetGrant> fleetGrantsForGroups(Collection<String> groups);

    /** The WHOLE ladder mapping (access-review, widen check, drift) — never keyed by a caller set. */
    List<LadderGrantRow> allLadderGrants();

    /** The WHOLE fleet mapping, including the env-bootstrap apex overlay. */
    List<FleetGrantRow> allFleetGrants();

    /** One ladder grant row in the effective mapping. */
    record LadderGrantRow(String group, Role role, String engineId, String tenantId, String source) {}

    /** One fleet grant row in the effective mapping. */
    record FleetGrantRow(String group, FleetGrant grant, String source) {}
}
