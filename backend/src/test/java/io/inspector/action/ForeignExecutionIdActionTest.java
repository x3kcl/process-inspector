package io.inspector.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.inspector.audit.AuditService;
import io.inspector.audit.ProtectedDefinitionRepository;
import io.inspector.audit.ProtectedInstance;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.audit.ProtectionGuard;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.ExecutionRecords;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.VariableRecords;
import io.inspector.config.AuditProperties;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.OidcProperties;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import io.inspector.security.reauth.DangerousActionReauthGate;
import io.inspector.support.TestEngines;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * #756 item 1, the mutating routes that take an {@code executionId}: an execution of ANOTHER
 * instance is refused ({@code 400 execution-instance-mismatch}) before any engine write — so the
 * protected-instance guard (R-SAFE-05), evaluated for the path instance, cannot be bypassed by
 * naming a protected instance's execution under an unprotected instance's path.
 */
class ForeignExecutionIdActionTest {

    private static final String DEV = "dev-engine";
    private static final String OPEN = "pi-open";
    private static final String GUARDED = "pi-guarded";

    private final ProcessApiClient client = mock(ProcessApiClient.class);
    private final AuditService audit = mock(AuditService.class);
    private final RbacAuthorizer rbac = mock(RbacAuthorizer.class);
    private final ProtectedInstanceRepository protectedInstances = mock(ProtectedInstanceRepository.class);
    private final ProtectedDefinitionRepository protectedDefinitions = mock(ProtectedDefinitionRepository.class);
    private final Authentication operator = new TestingAuthenticationToken("op", "n/a", "ROLE_OPERATOR");

    private CorrectiveActionService service;

    @BeforeEach
    void anOperatorAndAProtectedInstanceWhoseExecutionIsNamedUnderAnUnprotectedPath() {
        EngineRegistry registry = new EngineRegistry(new InspectorProperties(
                null,
                null,
                null,
                null,
                List.of(TestEngines.engine(DEV, "http://localhost:1", EngineEnvironment.DEV, EngineMode.READ_WRITE))));
        service = new CorrectiveActionService(
                registry,
                client,
                mock(CmmnApiClient.class),
                audit,
                rbac,
                new ProtectionGuard(protectedInstances, protectedDefinitions, rbac),
                new TicketPolicy(new AuditProperties(null, null, null)),
                new DangerousActionReauthGate(
                        new OidcProperties(null, false, null),
                        Clock.fixed(Instant.parse("2026-11-17T07:00:00Z"), ZoneOffset.UTC)));
        when(rbac.hasRoleOn(any(), any(), anyString())).thenReturn(true);
        when(rbac.hasRoleOn(any(), eq(Role.ADMIN), anyString())).thenReturn(false);
        when(protectedInstances.findById(any())).thenReturn(Optional.empty());
        when(protectedInstances.findById(new ProtectedInstance.Key(DEV, GUARDED)))
                .thenReturn(Optional.of(new ProtectedInstance(
                        DEV, GUARDED, "regulatory hold", "admin", Instant.parse("2026-11-01T00:00:00Z"))));
        when(protectedDefinitions.findById(any())).thenReturn(Optional.empty());
        when(client.getExecution(any(), eq(CallPriority.INTERACTIVE), eq("ex-guarded")))
                .thenReturn(ExecutionRecords.execution(
                        Map.of("id", "ex-guarded", "processInstanceId", GUARDED, "activityId", "pay")));
        when(client.getExecutionVariable(any(), eq(CallPriority.INTERACTIVE), eq("ex-guarded"), eq("amount")))
                .thenReturn(VariableRecords.variable(Map.of("name", "amount", "type", "integer", "value", 42)));
    }

    private static ActionRequest edit(String executionId) {
        return new ActionRequest(
                "fix the loop-local amount",
                null,
                null,
                null,
                null,
                null,
                new ActionRequest.VariableEdit("amount", "integer", 43, 42, executionId),
                null,
                null,
                null,
                null);
    }

    private static ActionRequest unstick(String executionId) {
        return new ActionRequest(
                "deliver the lost trigger",
                null,
                null,
                null,
                null,
                executionId,
                null,
                null,
                new ActionRequest.EventTrigger(ActionRequest.EventTrigger.TRIGGER, null),
                null,
                null);
    }

    private static String code(Throwable e) {
        return ((GuardRefusedException) e).code();
    }

    @Test
    void theProtectedInstanceIsRefusedOnItsOwnPath() {
        assertThatThrownBy(() -> service.execute(DEV, GUARDED, ActionVerb.EDIT_VARIABLE, edit("ex-guarded"), operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("instance-protected"));
        verify(client, never()).putExecutionVariable(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void aProtectedInstancesExecutionUnderAnUnprotectedPathIsRefusedBeforeAnyWrite() {
        assertThatThrownBy(() -> service.execute(DEV, OPEN, ActionVerb.EDIT_VARIABLE, edit("ex-guarded"), operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("execution-instance-mismatch"))
                .hasMessageContaining(GUARDED);
        verify(client, never()).putExecutionVariable(any(), any(), anyString(), anyString(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void unstickEventRefusesAnotherInstancesExecutionBeforeAnyWrite() {
        assertThatThrownBy(() -> service.execute(DEV, OPEN, ActionVerb.UNSTICK_EVENT, unstick("ex-guarded"), operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("execution-instance-mismatch"));
        verify(client, never()).executionAction(any(), any(), anyString(), any());
        verifyNoInteractions(audit);
    }
}
