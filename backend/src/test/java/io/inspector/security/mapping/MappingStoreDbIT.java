package io.inspector.security.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.security.Role;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Rung 4 (engine-harness), LOCAL-ONLY — the DB mapping store against a REAL Postgres 16
 * (Testcontainers, V13 applied, Hibernate validating). Like the other DB ITs this is NOT in
 * ci.yml's itClass matrix; CI proves the logic at rung 1 (FileMappingSourceTest,
 * ApexInvariantCheckerTest). Proves what the mocked rungs can't: the boot file-seed import ran,
 * DbMappingSource reads the store, the env-bootstrap apex overlays a real read, and refresh()
 * picks up a committed row.
 */
@SpringBootTest
@ActiveProfiles({"it-actions", "db"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MappingStoreDbIT {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("inspector.security.mapping.source", () -> "db");
        // The env-bootstrap apex (always-available floor) — overlaid on every read, never a store row.
        registry.add("inspector.security.mapping.access-admin-group", () -> "access-admins");
    }

    @Autowired
    DbMappingSource dbSource;

    @Autowired
    GroupScopeGrantRepository ladderRepo;

    @Autowired
    GroupFleetGrantRepository fleetRepo;

    @Test
    void bootSeededTheRegistryFleetGrantAndTheEnvApexOverlays() {
        // MappingSeeder ran at boot on the empty store: the config REGISTRY_ADMIN group migrated in.
        assertThat(fleetRepo.findAll())
                .anySatisfy(e -> assertThat(e.getGrantKind()).isEqualTo(FleetGrant.REGISTRY_ADMIN));

        // The env-bootstrap ACCESS_ADMIN is an OVERLAY (not a store row) but resolves on read.
        assertThat(dbSource.allFleetGrants()).anySatisfy(r -> {
            assertThat(r.group()).isEqualTo("access-admins");
            assertThat(r.grant()).isEqualTo(FleetGrant.ACCESS_ADMIN);
            assertThat(r.source()).isEqualTo("env-bootstrap");
        });
        assertThat(dbSource.fleetGrantsForGroups(List.of("access-admins"))).contains(FleetGrant.ACCESS_ADMIN);
    }

    @Test
    void refreshPicksUpACommittedLadderRow() {
        ladderRepo.save(
                new GroupScopeGrantEntity("orders-l2", Role.OPERATOR.name(), "orders-prod", "*", "ui", Instant.now()));
        dbSource.refresh();
        assertThat(dbSource.grantsForGroups(List.of("orders-l2"))).anySatisfy(g -> {
            assertThat(g.role()).isEqualTo(Role.OPERATOR);
            assertThat(g.engineId()).isEqualTo("orders-prod");
        });
        assertThat(dbSource.allLadderGrants())
                .anySatisfy(r -> assertThat(r.group()).isEqualTo("orders-l2"));
    }
}
