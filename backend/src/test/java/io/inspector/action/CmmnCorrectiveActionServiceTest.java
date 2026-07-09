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
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.FlowableEngineClient;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineHealth;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.support.TestEngines;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * Rung 1 for the CMMN-scope seam of the verb executor (Case Inspector Phase 3). The rails are
 * proven scope-neutral elsewhere ({@link CorrectiveActionServiceTest}); here we prove only what
 * the {@code scope=CMMN} branch changes: the 6.8+ capability gate runs BEFORE any engine byte,
 * the server-fresh restatement reads the {@code /cmmn-management} DLQ by-id and keys ownership on
 * {@code caseInstanceId}, the audit records the case id, and the one engine call is the CMMN move.
 * The over-the-wire proof (the move actually re-queues a real dead-lettered case job) is the
 * rung-4 {@code CmmnCorrectiveActionIT}; here the client is mocked for ordering + routing.
 */
class CmmnCorrectiveActionServiceTest {

    private static final String ENGINE = "cmmn-engine";
    private static final String CASE = "case-1";
    private static final String JOB = "j1";

    private final EngineConfig engine = TestEngines.engine(ENGINE, "http://engine.test/flowable-rest/service");
    private final FlowableEngineClient client = mock(FlowableEngineClient.class);
    private final AuditService audit = mock(AuditService.class);
    private final RbacAuthorizer rbac = mock(RbacAuthorizer.class);
    private final ProtectedInstanceRepository protectedInstances = mock(ProtectedInstanceRepository.class);
    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final Authentication operator = new TestingAuthenticationToken("op", "n/a", "ROLE_OPERATOR");

    private CorrectiveActionService service;
    private AuditEntry pendingEntry;

    @BeforeEach
    void setUp() {
        service = new CorrectiveActionService(
                registry,
                client,
                audit,
                rbac,
                protectedInstances,
                new TicketPolicy(new io.inspector.config.AuditProperties(null, null, null)));
        when(registry.require(ENGINE)).thenReturn(engine);
        when(rbac.hasRoleOn(any(), any(), anyString())).thenReturn(true);
        when(protectedInstances.findById(any())).thenReturn(Optional.empty());

        pendingEntry = new AuditEntry(
                UUID.nameUUIDFromBytes("audit".getBytes(StandardCharsets.UTF_8)),
                "corr-1",
                "op",
                Instant.parse("2026-07-08T12:00:00Z"),
                ENGINE,
                null,
                CASE,
                "retry-job",
                null,
                null,
                null,
                false);
        when(audit.beginPending(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(pendingEntry);
    }

    /** scopeType present (≥ 6.8) → the CMMN gate passes. */
    private void scopeCapable() {
        when(registry.healthOf(ENGINE))
                .thenReturn(new EngineHealth(
                        true, "?", null, 0L, new EngineCapabilities(true, true, true, true, true), null, null, null));
    }

    private void deadLetterJobOnCase(String caseInstanceId) {
        when(client.getCmmnDeadLetterJob(engine, JOB))
                .thenReturn(Map.of(
                        "id", JOB,
                        "caseInstanceId", caseInstanceId,
                        "caseDefinitionId", "def-uuid",
                        "planItemInstanceId", "pi-1",
                        "elementId", "failingService",
                        "exceptionMessage", "Unknown property used in expression: ${nonExistentBean.doStuff()}"));
    }

    private static ActionRequest retryRequest() {
        return new ActionRequest(null, null, null, JOB, null, null, null, null, null, null, null);
    }

    /** Delete is tier 3 → a reason is always required; this one is ≥ 10 chars so the gate passes. */
    private static ActionRequest deleteRequest(String reason) {
        return new ActionRequest(reason, null, null, JOB, null, null, null, null, null, null, null);
    }

    private ActionResult retry() {
        return service.execute(ActionScope.CMMN, ENGINE, CASE, ActionVerb.RETRY_JOB, retryRequest(), operator);
    }

    private ActionResult delete(Authentication who, ActionRequest request) {
        return service.execute(ActionScope.CMMN, ENGINE, CASE, ActionVerb.DELETE_DEADLETTER, request, who);
    }

    @Test
    void cmmnRetryHydratesByIdMovesTheCmmnJobAndAuditsTheCaseId() {
        scopeCapable();
        deadLetterJobOnCase(CASE);

        ActionResult result = retry();

        InOrder order = inOrder(audit, client);
        // the server-fresh restatement reads the cmmn-api DLQ by-id (before the audit gate),
        // NOT the process-api lane
        order.verify(client).getCmmnDeadLetterJob(engine, JOB);
        // the audit "instance" is the caseInstanceId (audit_entry keys on a generic id)
        order.verify(audit)
                .beginPending(eq("op"), eq(ENGINE), any(), eq(CASE), eq("retry-job"), any(), any(), any(), any());
        // and the one engine call is the CMMN move, never the BPMN one
        order.verify(client).moveCmmnDeadLetterJob(engine, JOB);
        order.verify(audit).close(eq(pendingEntry), eq(AuditOutcome.ok), eq(200), any(), eq(true));
        verify(client, never()).moveDeadLetterJob(any(), anyString());
        verify(client, never()).getJob(any(), any(), anyString());
        assertThat(result.deltaStatement()).contains(JOB).contains("executable queue");
    }

    @Test
    void preSixEightEngineIsRefusedBeforeAnyReadOrAudit() {
        // changeState present, scopeType absent — a pre-6.8 engine, DLQ-blind on the cmmn context.
        when(registry.healthOf(ENGINE))
                .thenReturn(new EngineHealth(
                        true, "?", null, 0L, new EngineCapabilities(true, true, false, false, true), null, null, null));

        assertThatThrownBy(this::retry)
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("capability-unavailable"));
        verifyNoInteractions(audit);
        verify(client, never()).getCmmnDeadLetterJob(any(), anyString());
        verify(client, never()).moveCmmnDeadLetterJob(any(), anyString());
    }

    @Test
    void unprobedEngineIsRefusedNotSentBlind() {
        when(registry.healthOf(ENGINE)).thenReturn(null); // no probe yet

        assertThatThrownBy(this::retry)
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("capability-unknown"));
        verifyNoInteractions(audit);
        verify(client, never()).moveCmmnDeadLetterJob(any(), anyString());
    }

    @Test
    void aVerbThatIsNotCmmnApplicableIsRefusedBeforeAudit() {
        scopeCapable();

        assertThatThrownBy(() ->
                        service.execute(ActionScope.CMMN, ENGINE, CASE, ActionVerb.SUSPEND, retryRequest(), operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("verb-not-cmmn-scoped"));
        verifyNoInteractions(audit);
        verify(client, never()).suspendOrActivateInstance(any(), anyString(), anyString());
    }

    @Test
    void aJobAlreadyGoneFromTheDlqIsAnHonestRefusalNotAMove() {
        scopeCapable();
        when(client.getCmmnDeadLetterJob(engine, JOB)).thenReturn(null); // 404 by-id

        assertThatThrownBy(this::retry)
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("job-gone"));
        verifyNoInteractions(audit);
        verify(client, never()).moveCmmnDeadLetterJob(any(), anyString());
    }

    @Test
    void aJobOwnedByAnotherCaseIsRefusedBeforeAudit() {
        scopeCapable();
        deadLetterJobOnCase("some-other-case");

        assertThatThrownBy(this::retry)
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("job-case-mismatch"));
        verifyNoInteractions(audit);
        verify(client, never()).moveCmmnDeadLetterJob(any(), anyString());
    }

    /* ------------------- delete-deadletter (tier 3 / ADMIN) — the second CMMN verb ------------------- */

    @Test
    void cmmnDeleteHydratesByIdDeletesTheCmmnJobAndAuditsTheCaseId() {
        scopeCapable();
        deadLetterJobOnCase(CASE);

        ActionResult result = delete(operator, deleteRequest("stale orphan job, case abandoned"));

        InOrder order = inOrder(audit, client);
        // same server-fresh by-id restatement as retry (before the audit gate) …
        order.verify(client).getCmmnDeadLetterJob(engine, JOB);
        order.verify(audit)
                .beginPending(
                        eq("op"), eq(ENGINE), any(), eq(CASE), eq("delete-deadletter"), any(), any(), any(), any());
        // … but the one engine call is the CMMN delete, never the move or the BPMN delete
        order.verify(client).deleteCmmnDeadLetterJob(engine, JOB);
        order.verify(audit).close(eq(pendingEntry), eq(AuditOutcome.ok), eq(200), any(), eq(true));
        verify(client, never()).moveCmmnDeadLetterJob(any(), anyString());
        verify(client, never()).deleteDeadLetterJob(any(), anyString());
        // scope-honest delta: a CMMN case has no change-state rescue in this tool
        assertThat(result.deltaStatement()).contains(JOB).contains("orphaned").contains("no change-state for cases");
    }

    @Test
    void deleteWithoutAReasonIsRefusedBeforeAudit() {
        scopeCapable();
        deadLetterJobOnCase(CASE);

        // tier-3 reason discipline (SPEC §6): a null reason is refused before any engine byte.
        assertThatThrownBy(() -> delete(operator, deleteRequest(null)))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("reason-required"));
        verifyNoInteractions(audit);
        verify(client, never()).deleteCmmnDeadLetterJob(any(), anyString());
    }

    @Test
    void deleteRequiresAdminAndIsRefusedForALesserRole() {
        // RESPONDER/OPERATOR clears retry (tier 0) but not delete (tier 3 / ADMIN).
        when(rbac.hasRoleOn(any(), eq(io.inspector.security.Role.ADMIN), anyString()))
                .thenReturn(false);

        assertThatThrownBy(() -> delete(operator, deleteRequest("stale orphan job, case abandoned")))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("rbac-denied"));
        verifyNoInteractions(audit);
        verify(client, never()).deleteCmmnDeadLetterJob(any(), anyString());
        // refused before any capability probe or engine read
        verify(client, never()).getCmmnDeadLetterJob(any(), anyString());
    }
}
