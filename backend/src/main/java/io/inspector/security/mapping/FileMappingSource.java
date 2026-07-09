package io.inspector.security.mapping;

import io.inspector.security.Role;
import io.inspector.security.ScopeGrant;
import io.inspector.security.ScopeMappingService;
import io.inspector.security.SecurityProperties;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The default mapping source ({@code !db}): today's mounted-YAML semantics (IDP-SECURITY.md §6).
 * Ladder resolution + enumeration delegate to the hot-reloaded {@link ScopeMappingService}; fleet
 * grants come from config — {@code REGISTRY_ADMIN} from the unchanged
 * {@code SecurityProperties.registryAdminGroup}, {@code ACCESS_ADMIN} from the env-bootstrap
 * {@code inspector.security.mapping.access-admin-group}. CRUD is off in this mode (S4 403s it);
 * the large rung-3 suite keeps this source with zero new mocks.
 */
@Component
@Profile("!db")
public class FileMappingSource implements MappingSource {

    private final ScopeMappingService file;
    private final SecurityProperties security;
    private final MappingProperties mapping;

    public FileMappingSource(ScopeMappingService file, SecurityProperties security, MappingProperties mapping) {
        this.file = file;
        this.security = security;
        this.mapping = mapping;
    }

    @Override
    public Set<ScopeGrant> grantsForGroups(Collection<String> groups) {
        return file.grantsForGroups(groups);
    }

    @Override
    public Set<Role> rolesForGroups(Collection<String> groups) {
        return file.rolesForGroups(groups);
    }

    @Override
    public Set<FleetGrant> fleetGrantsForGroups(Collection<String> groups) {
        Set<FleetGrant> grants = new LinkedHashSet<>();
        if (groups.contains(security.registryAdminGroupOrDefault())) {
            grants.add(FleetGrant.REGISTRY_ADMIN);
        }
        String apex = mapping.accessAdminGroupOrNull();
        if (apex != null && groups.contains(apex)) {
            grants.add(FleetGrant.ACCESS_ADMIN);
        }
        return grants;
    }

    @Override
    public List<LadderGrantRow> allLadderGrants() {
        List<LadderGrantRow> rows = new java.util.ArrayList<>();
        for (Map.Entry<String, List<ScopeGrant>> entry : file.allGrantsByGroup().entrySet()) {
            for (ScopeGrant g : entry.getValue()) {
                rows.add(new LadderGrantRow(entry.getKey(), g.role(), g.engineId(), g.tenantId(), "file-seed"));
            }
        }
        return List.copyOf(rows);
    }

    @Override
    public List<FleetGrantRow> allFleetGrants() {
        List<FleetGrantRow> rows = new java.util.ArrayList<>();
        rows.add(new FleetGrantRow(security.registryAdminGroupOrDefault(), FleetGrant.REGISTRY_ADMIN, "config"));
        String apex = mapping.accessAdminGroupOrNull();
        if (apex != null) {
            rows.add(new FleetGrantRow(apex, FleetGrant.ACCESS_ADMIN, "env-bootstrap"));
        }
        return List.copyOf(rows);
    }
}
