package io.inspector.audit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.inspector.action.GuardRefusedException;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * Rung 1 (#172) — the shared protection check every verb-executing service delegates to
 * (previously three verbatim-duplicated private methods; the issue's own review flagged the
 * duplication). Covers both scopes independently and together, plus the fail-closed RuntimeException
 * path each of the three original implementations had.
 */
class ProtectionGuardTest {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");

    private ProtectedInstanceRepository instances;
    private ProtectedDefinitionRepository definitions;
    private RbacAuthorizer rbac;
    private ProtectionGuard guard;
    private final Authentication admin = new TestingAuthenticationToken("admin1", "n/a", "ROLE_ADMIN");
    private final Authentication operator = new TestingAuthenticationToken("op1", "n/a", "ROLE_OPERATOR");

    @BeforeEach
    void setUp() {
        instances = mock(ProtectedInstanceRepository.class);
        definitions = mock(ProtectedDefinitionRepository.class);
        rbac = mock(RbacAuthorizer.class);
        guard = new ProtectionGuard(instances, definitions, rbac);
        when(instances.findById(any())).thenReturn(Optional.empty());
        when(definitions.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void bothScopesNullIsANoOpEvenWithoutAdmin() {
        assertThatCode(() -> guard.requireUnprotectedOrAdmin(operator, "engine-a", null, null, "retry-job"))
                .doesNotThrowAnyException();
    }

    @Test
    void anUnprotectedInstanceAndDefinitionNeverRefusesEvenWithoutAdmin() {
        assertThatCode(() -> guard.requireUnprotectedOrAdmin(operator, "engine-a", "pi-1", "payment", "retry-job"))
                .doesNotThrowAnyException();
    }

    @Test
    void adminBypassesAProtectedInstance() {
        when(instances.findById(any()))
                .thenReturn(Optional.of(new ProtectedInstance("engine-a", "pi-1", "hold", "admin0", NOW)));
        when(rbac.hasRoleOn(admin, Role.ADMIN, "engine-a")).thenReturn(true);

        assertThatCode(() -> guard.requireUnprotectedOrAdmin(admin, "engine-a", "pi-1", null, "retry-job"))
                .doesNotThrowAnyException();
    }

    @Test
    void aNonAdminIsRefusedOnAProtectedInstance() {
        when(instances.findById(any()))
                .thenReturn(Optional.of(new ProtectedInstance("engine-a", "pi-1", "regulatory hold", "admin0", NOW)));
        when(rbac.hasRoleOn(operator, Role.ADMIN, "engine-a")).thenReturn(false);

        assertThatThrownBy(() -> guard.requireUnprotectedOrAdmin(operator, "engine-a", "pi-1", null, "retry-job"))
                .isInstanceOf(GuardRefusedException.class)
                .extracting(e -> ((GuardRefusedException) e).status())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aNonAdminIsRefusedOnAProtectedDefinitionEvenWithNoInstanceIdChecked() {
        when(definitions.findById(any()))
                .thenReturn(Optional.of(new ProtectedDefinition("engine-a", "payment", "v3 freeze", "admin0", NOW)));
        when(rbac.hasRoleOn(operator, Role.ADMIN, "engine-a")).thenReturn(false);

        assertThatThrownBy(() ->
                        guard.requireUnprotectedOrAdmin(operator, "engine-a", null, "payment", "suspend-definition"))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> {
                    GuardRefusedException refused = (GuardRefusedException) e;
                    org.assertj.core.api.Assertions.assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    org.assertj.core.api.Assertions.assertThat(refused.code()).isEqualTo("definition-protected");
                });
    }

    @Test
    void adminBypassesAProtectedDefinition() {
        when(definitions.findById(any()))
                .thenReturn(Optional.of(new ProtectedDefinition("engine-a", "payment", "v3 freeze", "admin0", NOW)));
        when(rbac.hasRoleOn(admin, Role.ADMIN, "engine-a")).thenReturn(true);

        assertThatCode(() -> guard.requireUnprotectedOrAdmin(admin, "engine-a", null, "payment", "suspend-definition"))
                .doesNotThrowAnyException();
    }

    @Test
    void anUnreadableInstanceRegistryFailsClosed() {
        when(instances.findById(any())).thenThrow(new RuntimeException("postgres down"));

        assertThatThrownBy(() -> guard.requireUnprotectedOrAdmin(admin, "engine-a", "pi-1", null, "retry-job"))
                .isInstanceOf(AuditUnavailableException.class);
    }

    @Test
    void anUnreadableDefinitionRegistryFailsClosed() {
        when(definitions.findById(any())).thenThrow(new RuntimeException("postgres down"));

        assertThatThrownBy(
                        () -> guard.requireUnprotectedOrAdmin(admin, "engine-a", null, "payment", "suspend-definition"))
                .isInstanceOf(AuditUnavailableException.class);
    }
}
