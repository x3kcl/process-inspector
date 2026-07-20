package io.inspector.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.action.GuardRefusedException;
import io.inspector.audit.AuditService;
import io.inspector.audit.AuditUnavailableException;
import io.inspector.config.InspectorProperties;
import io.inspector.dto.AcknowledgeErrorGroupRequest;
import io.inspector.dto.ErrorGroup;
import io.inspector.dto.ErrorGroupAcknowledgement;
import io.inspector.dto.TriageDashboardResponse;
import io.inspector.dto.UnacknowledgeErrorGroupRequest;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

/**
 * Rung 1 — the R-BAU-01 acknowledge door's guard chain and audit discipline, pure over
 * mocks (mocking OUR OWN store is legitimate; no engine is involved — this verb never
 * touches one). The Spring-rung sibling ({@code ErrorGroupAckRbacSpringTest}) proves the
 * HTTP door; real-Postgres behavior belongs to the *IT suite.
 */
class ErrorGroupAckServiceTest {

    /** The CURRENT normalizer generation — never a literal, so an ALGO_VERSION bump
     * (#270 took it to v2) does not silently turn every fixture into a stale-generation
     * refusal instead of testing what it means to test. */
    private static final int ALGO = ErrorSignatureNormalizer.ALGO_VERSION;

    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    private ErrorGroupAckRepository repository;
    private TriageService triage;
    private AuditService audit;
    private RbacAuthorizer rbac;
    private ErrorGroupAckService service;
    private final Authentication operator = new TestingAuthenticationToken("op1", "n/a", "ROLE_OPERATOR");

    @BeforeEach
    void setUp() {
        repository = mock(ErrorGroupAckRepository.class);
        triage = mock(TriageService.class);
        audit = mock(AuditService.class);
        rbac = mock(RbacAuthorizer.class);
        when(rbac.hasRoleOn(any(), eq(Role.OPERATOR), anyString())).thenReturn(true);
        when(repository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        InspectorProperties props = new InspectorProperties(
                null, null, new InspectorProperties.Triage(null, null, null, 20), null, null, null);
        service = new ErrorGroupAckService(repository, triage, audit, rbac, Clock.fixed(NOW, ZoneOffset.UTC), props);
    }

    private void liveGroup(ErrorGroup... groups) {
        when(triage.dashboard(false))
                .thenReturn(new TriageDashboardResponse(
                        NOW.toString(), List.of(), Map.of(), Map.of(), List.of(groups), Map.of()));
    }

    private static ErrorGroup group(long total, Map<String, Map<String, Long>> countsByEngine) {
        return new ErrorGroup(
                "hash-1", ALGO, "java.lang.NullPointerException", "boom #", "boom 42", total, total, 0, countsByEngine);
    }

    private static AcknowledgeErrorGroupRequest ackRequest(String reason) {
        return new AcknowledgeErrorGroupRequest("hash-1", ALGO, reason, "OPS-7", null);
    }

    /* ---------------- acknowledge ---------------- */

    @Test
    void acknowledgePersistsOneRowPerLiveSliceWithServerResolvedBaselines() {
        liveGroup(group(
                15,
                Map.of(
                        "e1", Map.of("orders:v3", 10L, "orders:v2", 0L),
                        "e2", Map.of("billing:v1", 5L))));

        ErrorGroupAcknowledgement info = service.acknowledge(ackRequest("known outage, ticket filed"), operator);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ErrorGroupAck>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2);
        ErrorGroupAck orders = saved.getValue().stream()
                .filter(r -> r.getEngineId().equals("e1"))
                .findFirst()
                .orElseThrow();
        assertThat(orders.getDefinitionKey()).isEqualTo("orders");
        assertThat(orders.getAcknowledgedCount()).isEqualTo(10); // zero-filled v2 not counted
        assertThat(orders.getAcknowledgedMaxVersion()).isEqualTo(3);
        assertThat(orders.getAcknowledgedBy()).isEqualTo("op1");
        assertThat(orders.getTicketId()).isEqualTo("OPS-7");

        assertThat(info).isNotNull();
        assertThat(info.acknowledgedTotal()).isEqualTo(15);
        assertThat(info.resurfaced()).isFalse();
    }

    @Test
    void acknowledgeWritesTheConfigEventWithReasonColumnAndProvenance() {
        liveGroup(group(10, Map.of("e1", Map.of("orders:v3", 10L))));

        service.acknowledge(ackRequest("known outage, ticket filed"), operator);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .recordConfigEvent(
                        eq("error-group-acknowledge"),
                        eq("op1"),
                        eq(true),
                        eq("known outage, ticket filed"),
                        payload.capture());
        assertThat(payload.getValue())
                .containsEntry("signatureHash", "hash-1")
                .containsEntry("algoVersion", ALGO)
                .containsEntry("acknowledgedTotal", 10L)
                .containsEntry("ticketId", "OPS-7");
        assertThat(payload.getValue().get("slices")).isEqualTo(List.of("e1 · orders:v3 · 10"));
    }

    @Test
    void reacknowledgeReplacesThePreviousRowsWholesale() {
        liveGroup(group(20, Map.of("e1", Map.of("orders:v4", 20L))));
        ErrorGroupAck previous = new ErrorGroupAck(
                "hash-1", ALGO, "e1", "orders", "op0", "earlier acknowledge", null, NOW.minusSeconds(600), null, 10, 3);
        when(repository.findBySignatureHashAndAlgoVersion("hash-1", ALGO)).thenReturn(List.of(previous));

        service.acknowledge(ackRequest("still the same outage"), operator);

        verify(repository).deleteAll(List.of(previous));
        // The replacement rows carry FRESH baselines (external review: prove the reset,
        // not just that something was saved).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ErrorGroupAck>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
        assertThat(saved.getValue().getFirst().getAcknowledgedCount()).isEqualTo(20);
        assertThat(saved.getValue().getFirst().getAcknowledgedMaxVersion()).isEqualTo(4);
    }

    @Test
    void shortReasonIsRefusedBeforeAnythingHappens() {
        assertThatThrownBy(() -> service.acknowledge(ackRequest("too short"), operator))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("reason-too-short");
                });
        verify(repository, never()).saveAll(any());
        verify(audit, never()).recordConfigEvent(anyString(), anyString(), anyBoolean(), anyString(), any());
    }

    @Test
    void staleAlgoVersionIsRefused409() {
        assertThatThrownBy(() -> service.acknowledge(
                        new AcknowledgeErrorGroupRequest("hash-1", 99, "legitimate reason here", null, null), operator))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("error-class-algo-mismatch");
                });
    }

    @Test
    void drainedGroupIsRefused409() {
        liveGroup(); // no live groups
        assertThatThrownBy(() -> service.acknowledge(ackRequest("legitimate reason here"), operator))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("error-group-absent");
                });
        verify(repository, never()).deleteAll(any());
        verify(repository, never()).saveAll(any());
    }

    @Test
    void expiryMustBeParsableAndInTheFuture() {
        liveGroup(group(10, Map.of("e1", Map.of("orders:v3", 10L))));
        assertThatThrownBy(() -> service.acknowledge(
                        new AcknowledgeErrorGroupRequest("hash-1", ALGO, "legitimate reason here", null, "tomorrowish"),
                        operator))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("ack-expiry-invalid"));
        assertThatThrownBy(() -> service.acknowledge(
                        new AcknowledgeErrorGroupRequest(
                                "hash-1",
                                ALGO,
                                "legitimate reason here",
                                null,
                                NOW.minusSeconds(60).toString()),
                        operator))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("ack-expiry-in-past"));
    }

    @Test
    void operatorMustHoldTheRoleOnEveryEngineTheGroupFailsOn() {
        liveGroup(group(15, Map.of("e1", Map.of("orders:v3", 10L), "e2", Map.of("billing:v1", 5L))));
        when(rbac.hasRoleOn(any(), eq(Role.OPERATOR), eq("e2"))).thenReturn(false);

        assertThatThrownBy(() -> service.acknowledge(ackRequest("legitimate reason here"), operator))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.code()).isEqualTo("rbac-denied");
                    assertThat(e.getMessage()).contains("e2");
                });
        verify(repository, never()).saveAll(any());
    }

    @Test
    void auditFailureCompensatesTheStoreAndRefusesFailClosed() {
        liveGroup(group(10, Map.of("e1", Map.of("orders:v3", 10L))));
        ErrorGroupAck previous = new ErrorGroupAck(
                "hash-1", ALGO, "e1", "orders", "op0", "earlier acknowledge", null, NOW.minusSeconds(600), null, 5, 2);
        when(repository.findBySignatureHashAndAlgoVersion("hash-1", ALGO)).thenReturn(List.of(previous));
        when(audit.recordConfigEvent(anyString(), anyString(), anyBoolean(), anyString(), any()))
                .thenThrow(new AuditUnavailableException(new RuntimeException("db down")));

        assertThatThrownBy(() -> service.acknowledge(ackRequest("legitimate reason here"), operator))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        // Compensation, IN ORDER (external review): delete the previous rows, save the
        // fresh ones, then — after the audit refusal — delete the fresh rows and
        // re-insert the previous acknowledge.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ErrorGroupAck>> saveCalls = ArgumentCaptor.forClass(List.class);
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(repository);
        inOrder.verify(repository).deleteAll(List.of(previous));
        inOrder.verify(repository).saveAll(any()); // the fresh rows
        inOrder.verify(repository).deleteAll(any()); // compensation: fresh rows removed
        inOrder.verify(repository).saveAll(any()); // compensation: previous re-inserted
        verify(repository, org.mockito.Mockito.times(2)).saveAll(saveCalls.capture());
        List<ErrorGroupAck> reinserted = saveCalls.getAllValues().get(1);
        assertThat(reinserted).hasSize(1);
        assertThat(reinserted.getFirst().getAcknowledgedBy()).isEqualTo("op0");
    }

    /* ---------------- unacknowledge ---------------- */

    @Test
    void unacknowledgeDeletesTheRowsAndAudits() {
        ErrorGroupAck row = new ErrorGroupAck(
                "hash-1", ALGO, "e1", "orders", "op0", "earlier acknowledge", null, NOW.minusSeconds(600), null, 10, 3);
        when(repository.findBySignatureHashAndAlgoVersion("hash-1", ALGO)).thenReturn(List.of(row));

        service.unacknowledge(
                new UnacknowledgeErrorGroupRequest("hash-1", ALGO, "outage resolved, un-muting"), operator);

        verify(repository).deleteAll(List.of(row));
        verify(audit)
                .recordConfigEvent(
                        eq("error-group-unacknowledge"), eq("op1"), eq(true), eq("outage resolved, un-muting"), any());
    }

    @Test
    void unacknowledgeOfAnUnacknowledgedGroupIsRefused409() {
        when(repository.findBySignatureHashAndAlgoVersion("hash-1", ALGO)).thenReturn(List.of());
        assertThatThrownBy(() -> service.unacknowledge(
                        new UnacknowledgeErrorGroupRequest("hash-1", ALGO, "legitimate reason here"), operator))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("error-group-not-acknowledged");
                });
    }

    @Test
    void unacknowledgeRequiresTheReasonToo() {
        assertThatThrownBy(() ->
                        service.unacknowledge(new UnacknowledgeErrorGroupRequest("hash-1", ALGO, "short"), operator))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("reason-too-short"));
    }

    @Test
    void unacknowledgeAuditFailureReinsertsTheRows() {
        ErrorGroupAck row = new ErrorGroupAck(
                "hash-1", ALGO, "e1", "orders", "op0", "earlier acknowledge", null, NOW.minusSeconds(600), null, 10, 3);
        when(repository.findBySignatureHashAndAlgoVersion("hash-1", ALGO)).thenReturn(List.of(row));
        when(audit.recordConfigEvent(anyString(), anyString(), anyBoolean(), anyString(), any()))
                .thenThrow(new AuditUnavailableException(new RuntimeException("db down")));

        assertThatThrownBy(() -> service.unacknowledge(
                        new UnacknowledgeErrorGroupRequest("hash-1", ALGO, "outage resolved, un-muting"), operator))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(repository).saveAll(any()); // the detached re-insert
    }

    /* ---------------- decorate (the render-time join) ---------------- */

    @Test
    void decorateOverlaysAckStateWithoutTouchingTheCachedObjects() {
        ErrorGroup g = group(10, Map.of("e1", Map.of("orders:v3", 10L)));
        TriageDashboardResponse cached =
                new TriageDashboardResponse(NOW.toString(), List.of(), Map.of(), Map.of(), List.of(g), Map.of());
        ErrorGroupAck row = new ErrorGroupAck(
                "hash-1", ALGO, "e1", "orders", "op0", "known outage window", null, NOW.minusSeconds(600), null, 10, 3);
        when(repository.findAll()).thenReturn(List.of(row));

        TriageDashboardResponse decorated = service.decorate(cached);

        assertThat(decorated).isNotSameAs(cached);
        assertThat(cached.errorGroups().getFirst().acknowledgement()).isNull(); // cache untouched
        ErrorGroupAcknowledgement info = decorated.errorGroups().getFirst().acknowledgement();
        assertThat(info).isNotNull();
        assertThat(info.acknowledgedBy()).isEqualTo("op0");
        assertThat(info.resurfaced()).isFalse();
    }

    @Test
    void decorateIgnoresRowsFromAnotherNormalizerGeneration() {
        ErrorGroup g = group(10, Map.of("e1", Map.of("orders:v3", 10L)));
        TriageDashboardResponse cached =
                new TriageDashboardResponse(NOW.toString(), List.of(), Map.of(), Map.of(), List.of(g), Map.of());
        ErrorGroupAck staleGeneration = new ErrorGroupAck(
                "hash-1", 0, "e1", "orders", "op0", "acked under algo v0", null, NOW.minusSeconds(600), null, 10, 3);
        when(repository.findAll()).thenReturn(List.of(staleGeneration));

        TriageDashboardResponse decorated = service.decorate(cached);

        assertThat(decorated.errorGroups().getFirst().acknowledgement()).isNull();
    }

    @Test
    void decorateWithNoAcksReturnsTheCachedResponseAsIs() {
        ErrorGroup g = group(10, Map.of("e1", Map.of("orders:v3", 10L)));
        TriageDashboardResponse cached =
                new TriageDashboardResponse(NOW.toString(), List.of(), Map.of(), Map.of(), List.of(g), Map.of());
        when(repository.findAll()).thenReturn(List.of());
        assertThat(service.decorate(cached)).isSameAs(cached);
    }
}
