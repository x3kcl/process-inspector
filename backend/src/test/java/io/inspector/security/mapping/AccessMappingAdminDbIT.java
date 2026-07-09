package io.inspector.security.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.inspector.security.Role;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Rung 4 (R-SAFE-14), LOCAL-ONLY — the governed CRUD + four-eyes flow against a REAL Postgres.
 * CI proves the escalation logic at rung 1 (FourEyesPolicyTest) and the RBAC door at rung 3
 * (AdminAccessRbacSpringTest); this proves the end-to-end apply/propose/approve, self-approve
 * refusal, and the ≥2-apex invariant against real SQL.
 */
@SpringBootTest
@ActiveProfiles({"it-actions", "db"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessMappingAdminDbIT {

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
        registry.add("inspector.security.mapping.access-admin-group", () -> "access-admins"); // env apex #1
    }

    @Autowired
    AccessMappingAdminService svc;

    @Autowired
    DbMappingSource dbSource;

    @Autowired
    GroupFleetGrantRepository fleetRepo;

    private static Authentication actor(String user) {
        return new UsernamePasswordAuthenticationToken(
                user, "x", List.of(new SimpleGrantedAuthority("ROLE_ACCESS_ADMIN")));
    }

    /** CRUD needs ≥2 independent ACCESS_ADMIN groups: the env apex (#1) + a store row (#2). */
    @BeforeEach
    void enableCrud() {
        if (fleetRepo
                .findByGroupNameAndGrantKind("access-admins-2", "ACCESS_ADMIN")
                .isEmpty()) {
            fleetRepo.save(new GroupFleetGrantEntity("access-admins-2", FleetGrant.ACCESS_ADMIN, "ui", Instant.now()));
        }
        dbSource.refresh();
    }

    @Test
    void aScopedNonWideningLadderGrantAppliesSingleActor() {
        var outcome = svc.submit(
                GrantChange.ladderAdd("orders-l1", Role.RESPONDER, "orders-prod", "t1"),
                "grant orders l1 responder",
                actor("alice"));
        assertThat(outcome.status()).isEqualTo("applied");
        assertThat(dbSource.grantsForGroups(List.of("orders-l1")))
                .anySatisfy(g -> assertThat(g.role()).isEqualTo(Role.RESPONDER));
    }

    @Test
    void aWideningGrantBecomesAProposalThenAppliesOnIndependentApproval() {
        var proposed = svc.submit(
                GrantChange.fleetAdd("payments-admins", FleetGrant.REGISTRY_ADMIN),
                "payments team needs registry admin",
                actor("alice"));
        assertThat(proposed.status()).isEqualTo("proposed");
        assertThat(proposed.eligibleApproverGroups()).isNotEmpty();
        assertThat(dbSource.fleetGrantsForGroups(List.of("payments-admins"))).isEmpty(); // not applied yet

        var applied = svc.approve(proposed.proposalId(), actor("bob"));
        assertThat(applied.status()).isEqualTo("applied");
        assertThat(dbSource.fleetGrantsForGroups(List.of("payments-admins"))).contains(FleetGrant.REGISTRY_ADMIN);
    }

    @Test
    void theProposerCannotApproveTheirOwnProposal() {
        var proposed = svc.submit(
                GrantChange.fleetAdd("db-admins", FleetGrant.REGISTRY_ADMIN), "db team registry admin", actor("carol"));
        assertThatThrownBy(() -> svc.approve(proposed.proposalId(), actor("carol")))
                .isInstanceOf(AccessMappingAdminService.IneligibleApproverException.class);
    }

    @Test
    void removingAnApexThatWouldDropBelowTwoIsRefused() {
        // Only 2 apex groups resolve (env #1 + store #2); removing one would leave 1 → refuse.
        assertThatThrownBy(() -> svc.submit(
                        GrantChange.fleetRemove("access-admins-2", FleetGrant.ACCESS_ADMIN),
                        "trying to remove the second apex",
                        actor("alice")))
                .isInstanceOf(AccessMappingAdminService.ApexInvariantException.class);
    }
}
