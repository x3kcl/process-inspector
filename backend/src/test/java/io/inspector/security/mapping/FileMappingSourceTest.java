package io.inspector.security.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.security.Role;
import io.inspector.security.ScopeGrant;
import io.inspector.security.ScopeMappingService;
import io.inspector.security.SecurityProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * TS-MAP-01 — the default ({@code !db}) mapping source (IDP-SECURITY.md §6): ladder resolution +
 * whole-mapping enumeration delegate to the file, fleet grants come from config (registry-admin
 * group) + the env-bootstrap apex. Proves enumeration works under {@code mapping-source: file} —
 * never silently empty (⚠️ lead-dev).
 */
class FileMappingSourceTest {

    private FileMappingSource source(Path file) throws Exception {
        Files.writeString(file, """
                groups:
                  flowable-admins:
                    - role: ADMIN
                      engine-id: "*"
                  orders-l1:
                    - role: RESPONDER
                      engine-id: orders-prod
                """);
        SecurityProperties security = new SecurityProperties(null, file.toString(), 60, null, "registry-admin");
        ScopeMappingService scope = new ScopeMappingService(
                security, Clock.systemUTC(), Mockito.mock(io.inspector.audit.AuditService.class));
        MappingProperties mapping = new MappingProperties(null, "access-admins");
        return new FileMappingSource(scope, security, mapping);
    }

    @Test
    void ladderResolutionAndEnumerationComeFromTheFile(@TempDir Path dir) throws Exception {
        var s = source(dir.resolve("scopes.yml"));
        assertThat(s.grantsForGroups(List.of("flowable-admins"))).contains(new ScopeGrant(Role.ADMIN, "*", "*"));
        assertThat(s.rolesForGroups(List.of("orders-l1"))).containsExactly(Role.RESPONDER);
        assertThat(s.allLadderGrants())
                .hasSize(2)
                .allSatisfy(r -> assertThat(r.source()).isEqualTo("file-seed"));
    }

    @Test
    void fleetGrantsComeFromConfigAndTheEnvApex(@TempDir Path dir) throws Exception {
        var s = source(dir.resolve("scopes.yml"));
        assertThat(s.fleetGrantsForGroups(List.of("registry-admin"))).containsExactly(FleetGrant.REGISTRY_ADMIN);
        assertThat(s.fleetGrantsForGroups(List.of("access-admins"))).containsExactly(FleetGrant.ACCESS_ADMIN);
        assertThat(s.fleetGrantsForGroups(List.of("flowable-admins"))).isEmpty();

        assertThat(s.allFleetGrants())
                .anySatisfy(r -> {
                    assertThat(r.group()).isEqualTo("registry-admin");
                    assertThat(r.grant()).isEqualTo(FleetGrant.REGISTRY_ADMIN);
                })
                .anySatisfy(r -> {
                    assertThat(r.group()).isEqualTo("access-admins");
                    assertThat(r.grant()).isEqualTo(FleetGrant.ACCESS_ADMIN);
                    assertThat(r.source()).isEqualTo("env-bootstrap");
                });
    }

    @Test
    void withNoEnvApexOnlyTheRegistryFleetGrantIsPresent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("scopes.yml");
        Files.writeString(file, "groups: {}\n");
        SecurityProperties security = new SecurityProperties(null, file.toString(), 60, null, "registry-admin");
        ScopeMappingService scope = new ScopeMappingService(
                security, Clock.systemUTC(), Mockito.mock(io.inspector.audit.AuditService.class));
        var s = new FileMappingSource(scope, security, new MappingProperties(null, null));
        assertThat(s.allFleetGrants())
                .singleElement()
                .satisfies(r -> assertThat(r.grant()).isEqualTo(FleetGrant.REGISTRY_ADMIN));
    }
}
