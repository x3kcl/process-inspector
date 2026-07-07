package io.inspector.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditOutcome;
import io.inspector.audit.AuditService;
import io.inspector.audit.AuditUnavailableException;
import io.inspector.audit.ProtectedInstance;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.FlowableEngineClient;
import io.inspector.client.FlowableEngineClient.JobLaneKind;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import io.inspector.support.TestEngines;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Rung 1 for the guard chain and the audit ordering rails: refusals happen BEFORE the
 * audit gate and never touch the engine; the audit INSERT strictly precedes the engine
 * call (fail-closed, R-AUD-01); every post-dispatch failure closes the row honestly
 * (R-SEM-18). Engine wire semantics (the retry actually moving the job) are rung 4 —
 * CorrectiveActionIT; here the client is OUR seam, mocked for ordering only.
 */
class CorrectiveActionServiceTest {

    private static final String DEV = "dev-engine";
    private static final String PROD = "prod-engine";
    private static final String RO = "ro-engine";

    private final FlowableEngineClient client = mock(FlowableEngineClient.class);
    private final AuditService audit = mock(AuditService.class);
    private final RbacAuthorizer rbac = mock(RbacAuthorizer.class);
    private final ProtectedInstanceRepository protectedInstances = mock(ProtectedInstanceRepository.class);
    private final Authentication operator = new TestingAuthenticationToken("op", "n/a", "ROLE_OPERATOR");

    private CorrectiveActionService service;
    private AuditEntry pendingEntry;

    @BeforeEach
    void setUp() {
        EngineRegistry registry = new EngineRegistry(new InspectorProperties(
                null,
                null,
                null,
                null,
                List.of(
                        TestEngines.engine(DEV, "http://localhost:1", EngineEnvironment.DEV, EngineMode.READ_WRITE),
                        TestEngines.engine(PROD, "http://localhost:1", EngineEnvironment.PROD, EngineMode.READ_WRITE),
                        TestEngines.engine(RO, "http://localhost:1", EngineEnvironment.PROD, EngineMode.READ_ONLY))));
        service = new CorrectiveActionService(registry, client, audit, rbac, protectedInstances);

        when(rbac.hasRoleOn(any(), any(), anyString())).thenReturn(true);
        when(protectedInstances.findById(any())).thenReturn(Optional.empty());

        pendingEntry = new AuditEntry(
                UUID.nameUUIDFromBytes("audit".getBytes(StandardCharsets.UTF_8)),
                "corr-1",
                "op",
                Instant.parse("2026-07-06T12:00:00Z"),
                DEV,
                null,
                "pi-1",
                "retry-job",
                null,
                null,
                null,
                false);
        when(audit.beginPending(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(pendingEntry);

        when(client.getJob(any(), eq(JobLaneKind.DEADLETTER), eq("j1")))
                .thenReturn(Map.of("id", "j1", "processInstanceId", "pi-1", "elementId", "chargeCard"));
    }

    private static ActionRequest retryRequest() {
        return new ActionRequest(null, null, null, "j1", null, null, null, null, null, null, null);
    }

    /* ---------------- guard refusals: nothing audited, nothing sent ---------------- */

    @Test
    void rbacRefusalNeverTouchesAuditOrEngine() {
        when(rbac.hasRoleOn(any(), eq(Role.RESPONDER), eq(DEV))).thenReturn(false);

        assertThatThrownBy(() -> service.execute(DEV, "pi-1", ActionVerb.RETRY_JOB, retryRequest(), operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("rbac-denied"));
        verifyNoInteractions(audit);
        verifyNoInteractions(client);
    }

    @Test
    void readOnlyEngineRejectsEveryMutation() {
        assertThatThrownBy(() -> service.execute(RO, "pi-1", ActionVerb.RETRY_JOB, retryRequest(), operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("engine-read-only"));
        verifyNoInteractions(audit);
        verifyNoInteractions(client);
    }

    @Test
    void protectedInstanceRequiresAdmin() {
        when(protectedInstances.findById(any()))
                .thenReturn(Optional.of(new ProtectedInstance(
                        DEV, "pi-1", "regulatory hold — case 4711", "admin", Instant.parse("2026-07-01T00:00:00Z"))));
        when(rbac.hasRoleOn(any(), eq(Role.ADMIN), eq(DEV))).thenReturn(false);

        assertThatThrownBy(() -> service.execute(DEV, "pi-1", ActionVerb.RETRY_JOB, retryRequest(), operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("instance-protected"));
        verifyNoInteractions(audit);
    }

    @Test
    void tierOneRequiresReasonOnProdButNotOnDev() {
        when(client.getInstanceVariable(any(), eq("pi-1"), eq("amount")))
                .thenReturn(Map.of("name", "amount", "type", "integer", "value", 42));

        ActionRequest noReason = new ActionRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                new ActionRequest.VariableEdit("amount", "integer", 43, 42),
                null,
                null,
                null,
                null);

        // prod: refused before any engine write
        assertThatThrownBy(() -> service.execute(PROD, "pi-1", ActionVerb.EDIT_VARIABLE, noReason, operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("reason-required"));
        verify(client, never()).putInstanceVariable(any(), anyString(), anyString(), any());

        // dev: proceeds (SPEC §6 — tier-1 reason optional on dev/test)
        service.execute(DEV, "pi-1", ActionVerb.EDIT_VARIABLE, noReason, operator);
        verify(client).putInstanceVariable(any(), eq("pi-1"), eq("amount"), any());
    }

    @Test
    void shortReasonIsRefusedEvenWhereOptional() {
        ActionRequest shortReason =
                new ActionRequest("too short", null, null, "j1", null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.execute(DEV, "pi-1", ActionVerb.RETRY_JOB, shortReason, operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("reason-too-short"));
    }

    @Test
    void tierThreeOnProdDemandsTheServerFreshTypedToken() {
        when(client.getRuntimeProcessInstance(any(), eq("pi-1")))
                .thenReturn(Map.of("id", "pi-1", "businessKey", "ORD-77", "suspended", false));

        ActionRequest wrongToken = new ActionRequest(
                "operator requested teardown", null, "ORD-99", null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.execute(PROD, "pi-1", ActionVerb.TERMINATE_DELETE, wrongToken, operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("confirm-token-mismatch"));
        verify(client, never()).deleteProcessInstance(any(), anyString(), anyString());

        ActionRequest rightToken = new ActionRequest(
                "operator requested teardown", null, "ORD-77", null, null, null, null, null, null, null, null);
        service.execute(PROD, "pi-1", ActionVerb.TERMINATE_DELETE, rightToken, operator);
        verify(client).deleteProcessInstance(any(), eq("pi-1"), anyString());
    }

    /* ---------------- the fail-closed audit gate ---------------- */

    @Test
    void auditInsertStrictlyPrecedesTheEngineCall() {
        service.execute(DEV, "pi-1", ActionVerb.RETRY_JOB, retryRequest(), operator);

        InOrder order = inOrder(audit, client);
        order.verify(audit).beginPending(eq("op"), eq(DEV), any(), eq("pi-1"), eq("retry-job"), any(), any(), any());
        order.verify(client).moveDeadLetterJob(any(), eq("j1"));
        order.verify(audit).close(eq(pendingEntry), eq(AuditOutcome.ok), eq(200), any(), eq(true));
    }

    @Test
    void auditUnavailableAbortsBeforeAnyEngineMutation() {
        when(audit.beginPending(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new AuditUnavailableException(new RuntimeException("db down")));

        assertThatThrownBy(() -> service.execute(DEV, "pi-1", ActionVerb.RETRY_JOB, retryRequest(), operator))
                .isInstanceOf(AuditUnavailableException.class);
        verify(client, never()).moveDeadLetterJob(any(), anyString());
    }

    /* ---------------- CAS (R-SEM-09) ---------------- */

    @Test
    void casMismatchRefusesTheEditAuditsItAndLeavesTheEngineUntouched() {
        when(client.getInstanceVariable(any(), eq("pi-1"), eq("amount")))
                .thenReturn(Map.of("name", "amount", "type", "integer", "value", 100));

        ActionRequest edit = new ActionRequest(
                "fix poisoned amount",
                null,
                null,
                null,
                null,
                null,
                new ActionRequest.VariableEdit("amount", "integer", 43, 42),
                null,
                null,
                null,
                null);

        assertThatThrownBy(() -> service.execute(DEV, "pi-1", ActionVerb.EDIT_VARIABLE, edit, operator))
                .isInstanceOf(CasConflictException.class)
                .satisfies(e ->
                        assertThat(((CasConflictException) e).currentValue()).isEqualTo(100));
        verify(audit).close(eq(pendingEntry), eq(AuditOutcome.failed), eq(409), any(), eq(false));
        verify(client, never()).putInstanceVariable(any(), anyString(), anyString(), any());
    }

    @Test
    void casToleratesIntegerVersusLongFromTheWire() {
        when(client.getInstanceVariable(any(), eq("pi-1"), eq("amount")))
                .thenReturn(Map.of("name", "amount", "type", "long", "value", 42L));

        ActionRequest edit = new ActionRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                new ActionRequest.VariableEdit("amount", "long", 43, 42),
                null,
                null,
                null,
                null);

        service.execute(DEV, "pi-1", ActionVerb.EDIT_VARIABLE, edit, operator);
        verify(client).putInstanceVariable(any(), eq("pi-1"), eq("amount"), any());
    }

    /* ---------------- post-dispatch honesty (R-SEM-18) ---------------- */

    @Test
    void engineRejectionClosesFailedAndQuotesTheEngine() {
        org.mockito.Mockito.doThrow(HttpClientErrorException.create(
                        HttpStatus.CONFLICT,
                        "Conflict",
                        new HttpHeaders(),
                        "already suspended".getBytes(StandardCharsets.UTF_8),
                        null))
                .when(client)
                .moveDeadLetterJob(any(), eq("j1"));

        assertThatThrownBy(() -> service.execute(DEV, "pi-1", ActionVerb.RETRY_JOB, retryRequest(), operator))
                .isInstanceOf(EngineRejectedException.class)
                .satisfies(e ->
                        assertThat(((EngineRejectedException) e).engineBody()).contains("already suspended"));
        verify(audit).close(eq(pendingEntry), eq(AuditOutcome.failed), eq(409), any(), eq(false));
    }

    @Test
    void timeoutAfterDispatchIsUnknownNeverRetried() {
        org.mockito.Mockito.doThrow(
                        new ResourceAccessException("timeout", new HttpTimeoutException("request timed out")))
                .when(client)
                .moveDeadLetterJob(any(), eq("j1"));

        assertThatThrownBy(() -> service.execute(DEV, "pi-1", ActionVerb.RETRY_JOB, retryRequest(), operator))
                .isInstanceOf(OutcomeUnknownException.class);
        verify(audit).close(eq(pendingEntry), eq(AuditOutcome.unknown), any(), any(), eq(false));
        // exactly one dispatch — a blind retry can double-fire
        verify(client, org.mockito.Mockito.times(1)).moveDeadLetterJob(any(), eq("j1"));
    }

    @Test
    void connectionRefusedIsFailedBecauseNothingWasDispatched() {
        org.mockito.Mockito.doThrow(new ResourceAccessException("refused", new java.net.ConnectException("refused")))
                .when(client)
                .moveDeadLetterJob(any(), eq("j1"));

        assertThatThrownBy(() -> service.execute(DEV, "pi-1", ActionVerb.RETRY_JOB, retryRequest(), operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("engine-unreachable"));
        verify(audit).close(eq(pendingEntry), eq(AuditOutcome.failed), any(), any(), eq(false));
    }

    /* ---------------- reassign / return-to-team (v1.x #6) ---------------- */

    private static ActionRequest reassignRequest(String assignee) {
        return new ActionRequest(null, null, null, null, "task-9", null, null, null, null, null, assignee);
    }

    private void openTaskOn(String instanceId, String assignee) {
        java.util.Map<String, Object> task = new java.util.HashMap<>();
        task.put("id", "task-9");
        task.put("processInstanceId", instanceId);
        task.put("name", "Manual review");
        task.put("assignee", assignee);
        when(client.getTask(any(), eq("task-9"))).thenReturn(task);
    }

    @Test
    void reassignSetsTheAssigneeAuditsOldAndNewAndDispatchesOnce() {
        openTaskOn("pi-1", "kermit");

        var result = service.execute(DEV, "pi-1", ActionVerb.REASSIGN_TASK, reassignRequest("gonzo"), operator);

        InOrder order = inOrder(audit, client);
        order.verify(audit)
                .beginPending(eq("op"), eq(DEV), any(), eq("pi-1"), eq("reassign-task"), any(), any(), any());
        order.verify(client).setTaskAssignee(any(), eq("task-9"), eq("gonzo"));
        order.verify(audit).close(eq(pendingEntry), eq(AuditOutcome.ok), eq(200), any(), eq(true));
        assertThat(result.deltaStatement()).contains("reassigned to 'gonzo'").contains("was 'kermit'");
    }

    @Test
    void reassignWithoutAnAssigneeIsRefusedBeforeAudit() {
        openTaskOn("pi-1", "kermit");

        assertThatThrownBy(
                        () -> service.execute(DEV, "pi-1", ActionVerb.REASSIGN_TASK, reassignRequest(null), operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("missing-field"));
        verifyNoInteractions(audit);
        verify(client, never()).setTaskAssignee(any(), anyString(), any());
    }

    @Test
    void unassignClearsTheAssigneeSoItFallsBackToTheTeam() {
        openTaskOn("pi-1", "kermit");

        var result = service.execute(DEV, "pi-1", ActionVerb.UNASSIGN_TASK, reassignRequest(null), operator);

        verify(client).setTaskAssignee(any(), eq("task-9"), org.mockito.ArgumentMatchers.isNull());
        assertThat(result.deltaStatement()).contains("returned to its team").contains("was 'kermit'");
    }

    @Test
    void aTaskThatIsNoLongerActiveCannotBeReassigned() {
        when(client.getTask(any(), eq("task-9"))).thenReturn(null);

        assertThatThrownBy(() ->
                        service.execute(DEV, "pi-1", ActionVerb.REASSIGN_TASK, reassignRequest("gonzo"), operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("task-not-active"));
        verifyNoInteractions(audit);
        verify(client, never()).setTaskAssignee(any(), anyString(), any());
    }

    @Test
    void reassignRefusesATaskOwnedByAnotherInstance() {
        openTaskOn("OTHER", "kermit");

        assertThatThrownBy(() ->
                        service.execute(DEV, "pi-1", ActionVerb.REASSIGN_TASK, reassignRequest("gonzo"), operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("task-instance-mismatch"));
        verifyNoInteractions(audit);
    }

    @Test
    void jobBelongingToAnotherInstanceIsRefusedBeforeAudit() {
        when(client.getJob(any(), eq(JobLaneKind.DEADLETTER), eq("j1")))
                .thenReturn(Map.of("id", "j1", "processInstanceId", "OTHER"));

        assertThatThrownBy(() -> service.execute(DEV, "pi-1", ActionVerb.RETRY_JOB, retryRequest(), operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("job-instance-mismatch"));
        verifyNoInteractions(audit);
    }
}
