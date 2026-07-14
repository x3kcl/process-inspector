package io.inspector.audit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.action.GuardRefusedException;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

/**
 * Rung 1 (#165) — the R-SAFE-05 protect/unprotect guard chain and audit discipline, pure over
 * mocks (mocking our own store is legitimate; no engine is involved). The Spring-rung sibling
 * ({@code ProtectedInstanceRbacSpringTest}) proves the HTTP door and covers unprotect's
 * fail-closed compensation; this covers protect's.
 */
class ProtectedInstanceServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    private ProtectedInstanceRepository repository;
    private ProtectedDefinitionRepository definitionRepository;
    private AuditService audit;
    private RbacAuthorizer rbac;
    private ProtectedInstanceService service;
    private final Authentication admin = new TestingAuthenticationToken("admin1", "n/a", "ROLE_ADMIN");

    @BeforeEach
    void setUp() {
        repository = mock(ProtectedInstanceRepository.class);
        definitionRepository = mock(ProtectedDefinitionRepository.class);
        audit = mock(AuditService.class);
        rbac = mock(RbacAuthorizer.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ProtectedInstanceService(repository, definitionRepository, audit, rbac, clock);
        when(rbac.hasRoleOn(admin, Role.ADMIN, "engine-a")).thenReturn(true);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(definitionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void protectRefusesAShortReasonBeforeAnyStoreOrRbacCheck() {
        assertThatThrownBy(() -> service.protect(admin, "engine-a", "pi-1", "meh"))
                .isInstanceOf(GuardRefusedException.class)
                .extracting(e -> ((GuardRefusedException) e).status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(repository, never()).findById(any());
    }

    @Test
    void protectRefusesWithoutAdminOnTheEngine() {
        when(rbac.hasRoleOn(admin, Role.ADMIN, "engine-b")).thenReturn(false);
        assertThatThrownBy(() -> service.protect(admin, "engine-b", "pi-1", "regulatory hold pending review"))
                .isInstanceOf(GuardRefusedException.class)
                .extracting(e -> ((GuardRefusedException) e).status())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void protectRefusesAnAlreadyProtectedInstance() {
        when(repository.findById(any()))
                .thenReturn(Optional.of(new ProtectedInstance("engine-a", "pi-1", "prior hold", "admin0", NOW)));
        assertThatThrownBy(() -> service.protect(admin, "engine-a", "pi-1", "regulatory hold pending review"))
                .isInstanceOf(GuardRefusedException.class)
                .extracting(e -> ((GuardRefusedException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void protectRaceLosesToAConcurrentWinnerAsANamed409NotABare500() {
        when(repository.findById(any())).thenReturn(Optional.empty()); // this check saw it free
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup PK"));

        assertThatThrownBy(() -> service.protect(admin, "engine-a", "pi-1", "regulatory hold pending review"))
                .isInstanceOf(GuardRefusedException.class)
                .extracting(e -> ((GuardRefusedException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void protectSavesAndAuditsWithTheActorAndReason() {
        when(repository.findById(any())).thenReturn(Optional.empty());
        service.protect(admin, "engine-a", "pi-1", "  regulatory hold pending review  ");
        verify(audit)
                .recordConfigEvent(
                        "instance-protect",
                        "admin1",
                        true,
                        "regulatory hold pending review", // trimmed
                        java.util.Map.of(
                                "engineId",
                                "engine-a",
                                "instanceId",
                                "pi-1",
                                "reason",
                                "regulatory hold pending review"));
    }

    @Test
    void protectAuditFailureCompensatesTheStoreAndRefusesFailClosed() {
        when(repository.findById(any())).thenReturn(Optional.empty());
        when(audit.recordConfigEvent(anyString(), anyString(), anyBoolean(), any(), any()))
                .thenThrow(new AuditUnavailableException(new RuntimeException("postgres down")));

        assertThatThrownBy(() -> service.protect(admin, "engine-a", "pi-1", "regulatory hold pending review"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503");

        verify(repository).delete(any()); // the inserted row was removed (compensation)
    }

    @Test
    void unprotectRefusesAnUnprotectedInstance() {
        when(repository.findById(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.unprotect(admin, "engine-a", "pi-1", "hold lifted, resuming"))
                .isInstanceOf(GuardRefusedException.class)
                .extracting(e -> ((GuardRefusedException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
        verify(repository, never()).delete(any());
    }

    /* -------------------------- definition-key scope (#172) -------------------------- */

    @Test
    void protectDefinitionRefusesAShortReasonBeforeAnyStoreOrRbacCheck() {
        assertThatThrownBy(() -> service.protectDefinition(admin, "engine-a", "payment", "meh"))
                .isInstanceOf(GuardRefusedException.class)
                .extracting(e -> ((GuardRefusedException) e).status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(definitionRepository, never()).findById(any());
    }

    @Test
    void protectDefinitionRefusesWithoutAdminOnTheEngine() {
        when(rbac.hasRoleOn(admin, Role.ADMIN, "engine-b")).thenReturn(false);
        assertThatThrownBy(() -> service.protectDefinition(admin, "engine-b", "payment", "freeze during v3 rollout"))
                .isInstanceOf(GuardRefusedException.class)
                .extracting(e -> ((GuardRefusedException) e).status())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(definitionRepository, never()).saveAndFlush(any());
    }

    @Test
    void protectDefinitionRefusesAnAlreadyProtectedDefinition() {
        when(definitionRepository.findById(any()))
                .thenReturn(Optional.of(new ProtectedDefinition("engine-a", "payment", "prior freeze", "admin0", NOW)));
        assertThatThrownBy(() -> service.protectDefinition(admin, "engine-a", "payment", "freeze during v3 rollout"))
                .isInstanceOf(GuardRefusedException.class)
                .extracting(e -> ((GuardRefusedException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
        verify(definitionRepository, never()).saveAndFlush(any());
    }

    @Test
    void protectDefinitionRaceLosesToAConcurrentWinnerAsANamed409NotABare500() {
        when(definitionRepository.findById(any())).thenReturn(Optional.empty());
        when(definitionRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup PK"));

        assertThatThrownBy(() -> service.protectDefinition(admin, "engine-a", "payment", "freeze during v3 rollout"))
                .isInstanceOf(GuardRefusedException.class)
                .extracting(e -> ((GuardRefusedException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void protectDefinitionSavesAndAuditsWithTheActorAndReason() {
        when(definitionRepository.findById(any())).thenReturn(Optional.empty());
        service.protectDefinition(admin, "engine-a", "payment", "  freeze during v3 rollout  ");
        verify(audit)
                .recordConfigEvent(
                        "definition-protect",
                        "admin1",
                        true,
                        "freeze during v3 rollout", // trimmed
                        java.util.Map.of(
                                "engineId",
                                "engine-a",
                                "definitionKey",
                                "payment",
                                "reason",
                                "freeze during v3 rollout"));
    }

    @Test
    void protectDefinitionAuditFailureCompensatesTheStoreAndRefusesFailClosed() {
        when(definitionRepository.findById(any())).thenReturn(Optional.empty());
        when(audit.recordConfigEvent(anyString(), anyString(), anyBoolean(), any(), any()))
                .thenThrow(new AuditUnavailableException(new RuntimeException("postgres down")));

        assertThatThrownBy(() -> service.protectDefinition(admin, "engine-a", "payment", "freeze during v3 rollout"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503");

        verify(definitionRepository).delete(any()); // the inserted row was removed (compensation)
    }

    @Test
    void unprotectDefinitionRefusesAnUnprotectedDefinition() {
        when(definitionRepository.findById(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.unprotectDefinition(admin, "engine-a", "payment", "rollout stabilized"))
                .isInstanceOf(GuardRefusedException.class)
                .extracting(e -> ((GuardRefusedException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
        verify(definitionRepository, never()).delete(any());
    }
}
