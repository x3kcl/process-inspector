package io.inspector.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import io.inspector.audit.BreakGlassActor;
import io.inspector.audit.ProtectedInstance;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.ForwardedActor;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessApiClient.JobLaneKind;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.OidcProperties;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import io.inspector.security.reauth.DangerousActionReauthGate;
import io.inspector.security.reauth.ReauthRequiredException;
import io.inspector.support.TestEngines;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
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

    /** Fixed clock anchor for the OIDC freshness cases; the 15-min default window is applied off it. */
    private static final Instant NOW = Instant.parse("2026-07-09T12:00:00Z");

    private final ProcessApiClient client = mock(ProcessApiClient.class);
    private final CmmnApiClient cmmnClient = mock(CmmnApiClient.class);
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
        service = new CorrectiveActionService(
                registry,
                client,
                cmmnClient,
                audit,
                rbac,
                protectedInstances,
                new TicketPolicy(new io.inspector.config.AuditProperties(null, null, null)),
                new DangerousActionReauthGate(new OidcProperties(null, false, null), Clock.fixed(NOW, ZoneOffset.UTC)));

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
        when(audit.beginPending(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(pendingEntry);

        when(client.getJob(any(), eq(CallPriority.INTERACTIVE), eq(JobLaneKind.DEADLETTER), eq("j1")))
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
    void executeSetsTheBreakGlassMarkerFromTheAuthAndClearsIt() {
        // S7: on a bulk virtual-thread worker the SecurityContextHolder is empty, so the break-glass
        // flag on each per-item audit row must come from the BreakGlassActor marker this service sets
        // from the PASSED auth. Capture the marker at the instant the row is written, and prove it is
        // cleared afterward so it never leaks onto a reused carrier thread.
        Authentication breakGlass = new TestingAuthenticationToken("sealed", "n/a", "ROLE_ADMIN", "ROLE_BREAK_GLASS");
        when(rbac.isBreakGlass(breakGlass)).thenReturn(true);
        AtomicReference<Boolean> markerWhenRowWritten = new AtomicReference<>();
        when(audit.beginPending(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    markerWhenRowWritten.set(BreakGlassActor.current());
                    return pendingEntry;
                });
        org.springframework.security.core.context.SecurityContextHolder.clearContext();

        service.execute(DEV, "pi-1", ActionVerb.RETRY_JOB, retryRequest(), breakGlass);

        assertThat(markerWhenRowWritten.get()).isTrue();
        assertThat(BreakGlassActor.current()).isFalse();
    }

    @Test
    void executeDoesNotMarkBreakGlassForAnOrdinarySession() {
        when(rbac.isBreakGlass(operator)).thenReturn(false);
        AtomicReference<Boolean> markerWhenRowWritten = new AtomicReference<>();
        when(audit.beginPending(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    markerWhenRowWritten.set(BreakGlassActor.current());
                    return pendingEntry;
                });
        org.springframework.security.core.context.SecurityContextHolder.clearContext();

        service.execute(DEV, "pi-1", ActionVerb.RETRY_JOB, retryRequest(), operator);

        assertThat(markerWhenRowWritten.get()).isFalse();
        assertThat(BreakGlassActor.current()).isFalse();
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
        when(client.getInstanceVariable(any(), eq(CallPriority.INTERACTIVE), eq("pi-1"), eq("amount")))
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
        verify(client, never()).putInstanceVariable(any(), any(), anyString(), anyString(), any());

        // dev: proceeds (SPEC §6 — tier-1 reason optional on dev/test)
        service.execute(DEV, "pi-1", ActionVerb.EDIT_VARIABLE, noReason, operator);
        verify(client).putInstanceVariable(any(), eq(CallPriority.INTERACTIVE), eq("pi-1"), eq("amount"), any());
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
        when(client.getRuntimeProcessInstance(any(), eq(CallPriority.INTERACTIVE), eq("pi-1")))
                .thenReturn(Map.of("id", "pi-1", "businessKey", "ORD-77", "suspended", false));

        ActionRequest wrongToken = new ActionRequest(
                "operator requested teardown", null, "ORD-99", null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.execute(PROD, "pi-1", ActionVerb.TERMINATE_DELETE, wrongToken, operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("confirm-token-mismatch"));
        verify(client, never()).deleteProcessInstance(any(), any(), anyString(), anyString());

        ActionRequest rightToken = new ActionRequest(
                "operator requested teardown", null, "ORD-77", null, null, null, null, null, null, null, null);
        service.execute(PROD, "pi-1", ActionVerb.TERMINATE_DELETE, rightToken, operator);
        verify(client).deleteProcessInstance(any(), eq(CallPriority.INTERACTIVE), eq("pi-1"), anyString());
    }

    /* ---------------- dangerous-set re-auth (R-SAFE-07, IDP-SECURITY.md §5) ---------------- */

    /** An OIDC session whose auth_time is {@code age} old (null age = the IdP asserted no auth_time). */
    private static Authentication oidcSession(Duration age, String... roles) {
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("sub", "u-1");
        if (age != null) {
            claims.put("auth_time", NOW.minus(age).getEpochSecond());
        }
        OidcIdToken idToken =
                new OidcIdToken("id-tok", NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), claims);
        Collection<GrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority(r))
                .toList();
        return new OAuth2AuthenticationToken(new DefaultOidcUser(authorities, idToken), authorities, "oidc");
    }

    @Test
    void tierThreeOnAStaleOidcSessionDemandsReauthBeforeTheTypedTokenAndBeforeAudit() {
        // 20 min > the 15-min window → the freshness challenge fires FIRST: not confirm-token-mismatch
        // (the token check is downstream), and nothing is audited (a pre-audit refusal, like rbac).
        Authentication stale = oidcSession(Duration.ofMinutes(20), "ROLE_ADMIN");
        ActionRequest anyToken = new ActionRequest(
                "operator requested teardown", null, "whatever", null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.execute(PROD, "pi-1", ActionVerb.TERMINATE_DELETE, anyToken, stale))
                .isInstanceOf(ReauthRequiredException.class)
                .satisfies(e -> assertThat(((ReauthRequiredException) e).freshnessWindowSeconds())
                        .isEqualTo(900));
        verifyNoInteractions(audit);
        verifyNoInteractions(client);
    }

    @Test
    void tierThreeWithinTheWindowSkipsReauthAndReachesTheTypedTokenCheck() {
        // 5 min < window → fresh: re-auth passes and control reaches the tier-3 typed-token rail, which
        // then refuses the wrong token. Proves the gate lets a fresh session through to the next rail.
        when(client.getRuntimeProcessInstance(any(), eq(CallPriority.INTERACTIVE), eq("pi-1")))
                .thenReturn(Map.of("id", "pi-1", "businessKey", "ORD-77", "suspended", false));
        Authentication fresh = oidcSession(Duration.ofMinutes(5), "ROLE_ADMIN");
        ActionRequest wrongToken = new ActionRequest(
                "operator requested teardown", null, "ORD-99", null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.execute(PROD, "pi-1", ActionVerb.TERMINATE_DELETE, wrongToken, fresh))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("confirm-token-mismatch"));
        verify(client, never()).deleteProcessInstance(any(), any(), anyString(), anyString());
    }

    @Test
    void aTierZeroVerbNeverRequiresReauthEvenOnAnAncientOidcSession() {
        // The dangerous set is tier-3 only (no per-verb MFA storm): a day-old OIDC session still runs
        // a retry (tier 0) without a challenge. Gate scoping — the P1-fan-out safety valve.
        Authentication ancient = oidcSession(Duration.ofDays(1), "ROLE_ADMIN");
        service.execute(DEV, "pi-1", ActionVerb.RETRY_JOB, retryRequest(), ancient);
        verify(client).moveDeadLetterJob(any(), eq(CallPriority.INTERACTIVE), eq("j1"));
    }

    /* ---------------- the fail-closed audit gate ---------------- */

    @Test
    void auditInsertStrictlyPrecedesTheEngineCall() {
        service.execute(DEV, "pi-1", ActionVerb.RETRY_JOB, retryRequest(), operator);

        InOrder order = inOrder(audit, client);
        order.verify(audit)
                .beginPending(eq("op"), eq(DEV), any(), eq("pi-1"), eq("retry-job"), any(), any(), any(), any());
        order.verify(client).moveDeadLetterJob(any(), eq(CallPriority.INTERACTIVE), eq("j1"));
        order.verify(audit).close(eq(pendingEntry), eq(AuditOutcome.ok), eq(200), any(), eq(true));
    }

    @Test
    void forwardedActorEqualsTheAuditRowActorDuringDispatchAndIsClearedAfter() {
        // M4-CLOSEOUT §2 / D2a invariant: the X-Forwarded-User carried on the engine call is the
        // SAME actor the audit row was written with — captured here at the instant of dispatch.
        AtomicReference<String> seenAtDispatch = new AtomicReference<>();
        doAnswer(inv -> {
                    seenAtDispatch.set(ForwardedActor.current());
                    return null;
                })
                .when(client)
                .moveDeadLetterJob(any(), eq(CallPriority.INTERACTIVE), eq("j1"));

        service.execute(DEV, "pi-1", ActionVerb.RETRY_JOB, retryRequest(), operator);

        assertThat(seenAtDispatch.get()).isEqualTo(pendingEntry.forwardedIdentity());
        assertThat(seenAtDispatch.get()).isEqualTo(pendingEntry.getActor());
        // Cleared in the finally — never leaks onto the next unit of work on this thread.
        assertThat(ForwardedActor.current()).isNull();
    }

    @Test
    void auditUnavailableAbortsBeforeAnyEngineMutation() {
        when(audit.beginPending(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new AuditUnavailableException(new RuntimeException("db down")));

        assertThatThrownBy(() -> service.execute(DEV, "pi-1", ActionVerb.RETRY_JOB, retryRequest(), operator))
                .isInstanceOf(AuditUnavailableException.class);
        verify(client, never()).moveDeadLetterJob(any(), any(), anyString());
    }

    /* ---------------- CAS (R-SEM-09) ---------------- */

    @Test
    void casMismatchRefusesTheEditAuditsItAndLeavesTheEngineUntouched() {
        when(client.getInstanceVariable(any(), eq(CallPriority.INTERACTIVE), eq("pi-1"), eq("amount")))
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
        verify(client, never()).putInstanceVariable(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void casToleratesIntegerVersusLongFromTheWire() {
        when(client.getInstanceVariable(any(), eq(CallPriority.INTERACTIVE), eq("pi-1"), eq("amount")))
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
        verify(client).putInstanceVariable(any(), eq(CallPriority.INTERACTIVE), eq("pi-1"), eq("amount"), any());
    }

    /* ---------------- execution-local (step-local) edits ---------------- */

    private ActionRequest stepLocalEdit(String executionId, Object newValue, Object expectedOld) {
        return new ActionRequest(
                "fix the loop-local amount",
                null,
                null,
                null,
                null,
                null,
                new ActionRequest.VariableEdit("amount", "integer", newValue, expectedOld, executionId),
                null,
                null,
                null,
                null);
    }

    @Test
    void stepLocalEditReadsAndWritesTheExecutionLocalScopeOnly() {
        when(client.getExecution(any(), eq(CallPriority.INTERACTIVE), eq("exec-7")))
                .thenReturn(Map.of("id", "exec-7", "processInstanceId", "pi-1", "activityId", "validateLine"));
        when(client.getExecutionVariable(any(), eq(CallPriority.INTERACTIVE), eq("exec-7"), eq("amount")))
                .thenReturn(Map.of("name", "amount", "type", "integer", "value", 42));

        service.execute(DEV, "pi-1", ActionVerb.EDIT_VARIABLE, stepLocalEdit("exec-7", 43, 42), operator);

        // The write lands ON the execution with scope=local — never promoted to process scope.
        verify(client)
                .putExecutionVariable(
                        any(),
                        eq(CallPriority.INTERACTIVE),
                        eq("exec-7"),
                        eq("amount"),
                        argThat(body -> "local".equals(body.get("scope"))));
        verify(client, never()).putInstanceVariable(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void stepLocalCasComparesAgainstTheLocalValueNotTheProcessScope() {
        when(client.getExecution(any(), eq(CallPriority.INTERACTIVE), eq("exec-7")))
                .thenReturn(Map.of("id", "exec-7", "processInstanceId", "pi-1", "activityId", "validateLine"));
        // The local value (99) differs from what the operator saw (42) — CAS must refuse.
        when(client.getExecutionVariable(any(), eq(CallPriority.INTERACTIVE), eq("exec-7"), eq("amount")))
                .thenReturn(Map.of("name", "amount", "type", "integer", "value", 99));

        assertThatThrownBy(() -> service.execute(
                        DEV, "pi-1", ActionVerb.EDIT_VARIABLE, stepLocalEdit("exec-7", 43, 42), operator))
                .isInstanceOf(CasConflictException.class)
                .satisfies(e ->
                        assertThat(((CasConflictException) e).currentValue()).isEqualTo(99));
        verify(audit).close(eq(pendingEntry), eq(AuditOutcome.failed), eq(409), any(), eq(false));
        verify(client, never()).putExecutionVariable(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void stepLocalEditRefusesAForeignExecutionBeforeAuditing() {
        when(client.getExecution(any(), eq(CallPriority.INTERACTIVE), eq("exec-7")))
                .thenReturn(Map.of("id", "exec-7", "processInstanceId", "pi-OTHER"));

        assertThatThrownBy(() -> service.execute(
                        DEV, "pi-1", ActionVerb.EDIT_VARIABLE, stepLocalEdit("exec-7", 43, 42), operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(
                        e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("execution-instance-mismatch"));
        verifyNoInteractions(audit);
        verify(client, never()).putExecutionVariable(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void stepLocalEditRefusesWhenTheLocalVariableIsMissing() {
        when(client.getExecution(any(), eq(CallPriority.INTERACTIVE), eq("exec-7")))
                .thenReturn(Map.of("id", "exec-7", "processInstanceId", "pi-1"));
        when(client.getExecutionVariable(any(), eq(CallPriority.INTERACTIVE), eq("exec-7"), eq("amount")))
                .thenReturn(null);

        assertThatThrownBy(() -> service.execute(
                        DEV, "pi-1", ActionVerb.EDIT_VARIABLE, stepLocalEdit("exec-7", 43, 42), operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("variable-not-found"));
        verifyNoInteractions(audit);
        verify(client, never()).putExecutionVariable(any(), any(), anyString(), anyString(), any());
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
                .moveDeadLetterJob(any(), eq(CallPriority.INTERACTIVE), eq("j1"));

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
                .moveDeadLetterJob(any(), eq(CallPriority.INTERACTIVE), eq("j1"));

        assertThatThrownBy(() -> service.execute(DEV, "pi-1", ActionVerb.RETRY_JOB, retryRequest(), operator))
                .isInstanceOf(OutcomeUnknownException.class);
        verify(audit).close(eq(pendingEntry), eq(AuditOutcome.unknown), any(), any(), eq(false));
        // exactly one dispatch — a blind retry can double-fire
        verify(client, org.mockito.Mockito.times(1)).moveDeadLetterJob(any(), eq(CallPriority.INTERACTIVE), eq("j1"));
    }

    @Test
    void connectionRefusedIsFailedBecauseNothingWasDispatched() {
        org.mockito.Mockito.doThrow(new ResourceAccessException("refused", new java.net.ConnectException("refused")))
                .when(client)
                .moveDeadLetterJob(any(), eq(CallPriority.INTERACTIVE), eq("j1"));

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
        when(client.getTask(any(), eq(CallPriority.INTERACTIVE), eq("task-9"))).thenReturn(task);
    }

    @Test
    void reassignSetsTheAssigneeAuditsOldAndNewAndDispatchesOnce() {
        openTaskOn("pi-1", "kermit");

        var result = service.execute(DEV, "pi-1", ActionVerb.REASSIGN_TASK, reassignRequest("gonzo"), operator);

        InOrder order = inOrder(audit, client);
        order.verify(audit)
                .beginPending(eq("op"), eq(DEV), any(), eq("pi-1"), eq("reassign-task"), any(), any(), any(), any());
        order.verify(client).setTaskAssignee(any(), eq(CallPriority.INTERACTIVE), eq("task-9"), eq("gonzo"));
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
        verify(client, never()).setTaskAssignee(any(), any(), anyString(), any());
    }

    @Test
    void unassignClearsTheAssigneeSoItFallsBackToTheTeam() {
        openTaskOn("pi-1", "kermit");

        var result = service.execute(DEV, "pi-1", ActionVerb.UNASSIGN_TASK, reassignRequest(null), operator);

        verify(client)
                .setTaskAssignee(
                        any(), eq(CallPriority.INTERACTIVE), eq("task-9"), org.mockito.ArgumentMatchers.isNull());
        assertThat(result.deltaStatement()).contains("returned to its team").contains("was 'kermit'");
    }

    @Test
    void aTaskThatIsNoLongerActiveCannotBeReassigned() {
        when(client.getTask(any(), eq(CallPriority.INTERACTIVE), eq("task-9"))).thenReturn(null);

        assertThatThrownBy(() ->
                        service.execute(DEV, "pi-1", ActionVerb.REASSIGN_TASK, reassignRequest("gonzo"), operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("task-not-active"));
        verifyNoInteractions(audit);
        verify(client, never()).setTaskAssignee(any(), any(), anyString(), any());
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
        when(client.getJob(any(), eq(CallPriority.INTERACTIVE), eq(JobLaneKind.DEADLETTER), eq("j1")))
                .thenReturn(Map.of("id", "j1", "processInstanceId", "OTHER"));

        assertThatThrownBy(() -> service.execute(DEV, "pi-1", ActionVerb.RETRY_JOB, retryRequest(), operator))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> assertThat(((GuardRefusedException) e).code()).isEqualTo("job-instance-mismatch"));
        verifyNoInteractions(audit);
    }

    /* ---------------- tier-0 outcome toasts (usability W2 #2, SPEC §5.0/§6) ---------------- */

    private static ActionRequest emptyRequest() {
        return new ActionRequest(null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void suspendDeltaStatementStatesTheDeltaAndNamesTheCompensatingVerb() {
        when(client.getRuntimeProcessInstance(any(), eq(CallPriority.INTERACTIVE), eq("pi-1")))
                .thenReturn(Map.of("id", "pi-1"));

        var result = service.execute(DEV, "pi-1", ActionVerb.SUSPEND, emptyRequest(), operator);

        // SPEC §6 tier 0: explicit delta + reversibility phrasing, never a bare "success".
        assertThat(result.deltaStatement())
                .contains("pi-1")
                .contains("suspended")
                .contains("reversible")
                .contains("Activate resumes it");
    }

    @Test
    void suspendDeltaStatementNeverClaimsAJobLaneMoveItCannotVerify() {
        // Issue #117: suspending an instance does NOT move a dead-letter job to the suspended
        // lane, and the BFF never inspects job lanes on this path — so the toast must not
        // assert any lane movement (quiet-lie / R-TEST-03).
        when(client.getRuntimeProcessInstance(any(), eq(CallPriority.INTERACTIVE), eq("pi-1")))
                .thenReturn(Map.of("id", "pi-1"));

        var result = service.execute(DEV, "pi-1", ActionVerb.SUSPEND, emptyRequest(), operator);

        assertThat(result.deltaStatement()).doesNotContain("suspended lane").doesNotContain("jobs moved");
    }

    @Test
    void activateDeltaStatementStatesTheDeltaAndNamesTheCompensatingVerb() {
        when(client.getRuntimeProcessInstance(any(), eq(CallPriority.INTERACTIVE), eq("pi-1")))
                .thenReturn(Map.of("id", "pi-1"));

        var result = service.execute(DEV, "pi-1", ActionVerb.ACTIVATE, emptyRequest(), operator);

        assertThat(result.deltaStatement())
                .contains("pi-1")
                .contains("activated")
                .contains("reversible")
                .contains("Suspend undoes it");
    }

    @Test
    void activateDeltaStatementNeverClaimsAJobLaneMoveItCannotVerify() {
        // Mirror of #117 for the compensating verb: a dead-letter job was never suspended,
        // so "suspended jobs returned to their queues" is equally unverifiable here.
        when(client.getRuntimeProcessInstance(any(), eq(CallPriority.INTERACTIVE), eq("pi-1")))
                .thenReturn(Map.of("id", "pi-1"));

        var result = service.execute(DEV, "pi-1", ActionVerb.ACTIVATE, emptyRequest(), operator);

        assertThat(result.deltaStatement())
                .doesNotContain("returned to their queues")
                .doesNotContain("suspended lane");
    }
}
