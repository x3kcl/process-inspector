package io.inspector.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.action.ActionVerb;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.support.TestEngines;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * TS-RBAC-01 (risk rank R2, R-TEST-01 100%-matrix floor): the <b>generated</b> verb × role
 * decision matrix over {@link RbacAuthorizer#canExecute} — the pure permission gate behind
 * every {@code @PreAuthorize}. The matrix is derived from {@link ActionVerb#values()} × every
 * {@link Role}, so a newly-added verb WITHOUT a role floor fails this test the moment it lands
 * (the register's "CI fails on an endpoint without matrix rows" rule made mechanical). The
 * HTTP wiring of the same decision is proven at rung 3 in
 * {@code io.inspector.api.ActionRbacGuardSpringTest}; scoped-grant (engine/tenant) isolation
 * and read-only-engine mode are proven in {@link ScopeMappingServiceTest} and
 * {@code io.inspector.action.CorrectiveActionServiceTest} respectively.
 */
class RbacGuardMatrixTest {

    private static final String ENGINE = "probe-dev";

    private final RbacAuthorizer rbac = newAuthorizer();

    private static RbacAuthorizer newAuthorizer() {
        List<EngineConfig> engines = List.of(TestEngines.engine(ENGINE, "http://localhost:1/flowable-rest"));
        InspectorProperties registry = new InspectorProperties(null, null, null, null, engines);
        SecurityProperties props = new SecurityProperties(null, null, null, null, null);
        // No scope-mapping file here (null) → the audit path is never exercised; a mock suffices.
        ScopeMappingService scopeMapping = new ScopeMappingService(
                props, Clock.systemUTC(), org.mockito.Mockito.mock(io.inspector.audit.AuditService.class));
        // Dev/basic sessions only in this matrix → the OIDC group path is never exercised; a mock
        // resolver suffices (the OIDC branches are covered in OidcGroupResolverTest).
        return new RbacAuthorizer(scopeMapping, props, registry, org.mockito.Mockito.mock(OidcGroupResolver.class));
    }

    /** A dev/basic session carrying exactly one {@code ROLE_*} authority = global scope. */
    private static Authentication as(Role role) {
        return new UsernamePasswordAuthenticationToken(
                role.name().toLowerCase(), "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }

    /** Every (verb, role) pair — the matrix source. Grows automatically with the enum. */
    static Stream<Arguments> verbRoleMatrix() {
        List<Arguments> rows = new ArrayList<>();
        for (ActionVerb verb : ActionVerb.values()) {
            for (Role role : Role.values()) {
                rows.add(Arguments.of(verb, role));
            }
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "{1} on {0} → allowed iff role ≥ floor")
    @MethodSource("verbRoleMatrix")
    void everyVerbGrantsExactlyAtItsRoleFloor(ActionVerb verb, Role role) {
        boolean expected = role.atLeast(verb.minRole());
        assertThat(rbac.canExecute(as(role), ENGINE, verb.path()))
                .as("%s executing %s (floor %s)", role, verb.path(), verb.minRole())
                .isEqualTo(expected);
    }

    @Test
    void theMatrixCoversEverySingleTargetVerb() {
        // Guards R-TEST-01 "CI fails on an endpoint without matrix rows": the parameterized
        // source iterates ActionVerb.values(), so this asserts none were silently skipped.
        long rows = verbRoleMatrix().count();
        assertThat(rows).isEqualTo((long) ActionVerb.values().length * Role.values().length);
        assertThat(ActionVerb.values().length).isGreaterThanOrEqualTo(13);
    }

    @Test
    void noMutatingVerbIsEverReachableByAViewer() {
        // A VIEWER-executable mutation would be a quiet guard bypass = Sev1 by taxonomy.
        for (ActionVerb verb : ActionVerb.values()) {
            assertThat(rbac.canExecute(as(Role.VIEWER), ENGINE, verb.path()))
                    .as("VIEWER must never execute %s", verb.path())
                    .isFalse();
            assertThat(verb.minRole())
                    .as("%s role floor must be above VIEWER", verb.path())
                    .isNotEqualTo(Role.VIEWER);
        }
    }

    @Test
    void unauthenticatedAndAnonymousSessionsAreDeniedEveryVerb() {
        Authentication anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        for (ActionVerb verb : ActionVerb.values()) {
            assertThat(rbac.canExecute(null, ENGINE, verb.path()))
                    .as("null auth denied for %s", verb.path())
                    .isFalse();
            assertThat(rbac.canExecute(anonymous, ENGINE, verb.path()))
                    .as("anonymous denied for %s", verb.path())
                    .isFalse();
        }
    }

    @Test
    void unknownVerbPassesRbacSoTheControllerCanAnswer404() {
        // A typo must surface as 404, never masquerade as a 403 permission problem.
        assertThat(rbac.canExecute(as(Role.ADMIN), ENGINE, "reticulate-splines"))
                .isTrue();
        assertThat(rbac.canExecute(as(Role.VIEWER), ENGINE, "reticulate-splines"))
                .isTrue();
    }

    @Test
    void tierThreeVerbsAllDemandAdmin_andTierZeroFloorsAtResponder() {
        for (ActionVerb verb : ActionVerb.values()) {
            if (verb.tier() >= 3) {
                assertThat(verb.minRole())
                        .as("tier-3 verb %s must require ADMIN", verb.path())
                        .isEqualTo(Role.ADMIN);
            }
            if (verb.tier() == 0) {
                assertThat(verb.minRole())
                        .as("tier-0 verb %s floors at RESPONDER (the L1/L2 runbook tier)", verb.path())
                        .isEqualTo(Role.RESPONDER);
            }
        }
    }

    @Test
    void verbCatalogIsStructurallyConsistent() {
        List<String> paths =
                Arrays.stream(ActionVerb.values()).map(ActionVerb::path).toList();
        // No duplicate path segments — a collision would route two verbs to one guard.
        assertThat(paths).doesNotHaveDuplicates();
        for (ActionVerb verb : ActionVerb.values()) {
            assertThat(verb.path()).as("verb path is a slug").matches("[a-z][a-z-]*");
            assertThat(ActionVerb.fromPath(verb.path())).contains(verb);
            boolean definitionScoped = verb.path().endsWith("-definition");
            assertThat(verb.targetKind() == ActionVerb.TargetKind.DEFINITION)
                    .as("%s targetKind matches its path shape", verb.path())
                    .isEqualTo(definitionScoped);
        }
        assertThat(ActionVerb.fromPath("nope")).isEmpty();
    }
}
