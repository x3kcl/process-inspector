package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditEntryRepository;
import io.inspector.audit.AuditService;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

/**
 * R-AUD-10 read-side RBAC (security M5): config-event rows carry the {@code _inspector} sentinel
 * engineId that no operator is scoped to, so the usual per-engine OPERATOR gate would hide them
 * from every engine-scoped admin. They are visible to ANY ADMIN instead; every other row keeps
 * the per-engine OPERATOR+ payload gate.
 */
class AuditControllerTest {

    private final AuditEntryRepository repository = mock(AuditEntryRepository.class);
    private final RbacAuthorizer rbac = mock(RbacAuthorizer.class);
    private final AuditController controller = new AuditController(repository, rbac);

    private static AuditEntry row(String engineId) {
        return new AuditEntry(
                UUID.randomUUID(),
                "corr",
                "system",
                Instant.parse("2026-07-09T00:00:00Z"),
                engineId,
                null,
                null,
                "config-scope-mapping-reload",
                null,
                null,
                "{\"sha256\":\"x\"}",
                false);
    }

    @Test
    void configEventPayloadVisibleToAnyAdminButEngineRowStaysOperatorGated() {
        AuditEntry configRow = row(AuditService.CONFIG_ENGINE_ID);
        AuditEntry engineRow = row("engine-a");
        when(repository.findLog(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(configRow, engineRow));

        Authentication auth = mock(Authentication.class);
        // An engine-scoped admin: ADMIN somewhere, but NOT OPERATOR on engine-a.
        when(rbac.atLeast(auth, "ADMIN")).thenReturn(true);
        when(rbac.hasRoleOn(auth, Role.OPERATOR, "engine-a")).thenReturn(false);

        List<AuditController.AuditEntryDto> dtos = controller.operationsLog(null, null, null, null, 100, auth);

        assertThat(dtos.get(0).payload()).isNotNull(); // config-event row → visible to any ADMIN
        assertThat(dtos.get(1).payload()).isNull(); // engine row → still OPERATOR-gated per engine
    }

    @Test
    void configEventPayloadHiddenFromANonAdmin() {
        AuditEntry configRow = row(AuditService.CONFIG_ENGINE_ID);
        when(repository.findLog(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(configRow));

        Authentication auth = mock(Authentication.class);
        when(rbac.atLeast(auth, "ADMIN")).thenReturn(false);

        List<AuditController.AuditEntryDto> dtos = controller.operationsLog(null, null, null, null, 100, auth);

        assertThat(dtos.get(0).payload()).isNull();
    }
}
