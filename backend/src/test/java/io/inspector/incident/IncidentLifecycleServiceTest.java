package io.inspector.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.action.GuardRefusedException;
import io.inspector.audit.AuditService;
import io.inspector.audit.AuditUnavailableException;
import io.inspector.dto.AcknowledgeErrorGroupRequest;
import io.inspector.dto.IncidentResolution;
import io.inspector.dto.IncidentSummary;
import io.inspector.dto.ReopenIncidentRequest;
import io.inspector.dto.ResolveIncidentRequest;
import io.inspector.triage.ErrorGroupAckService;
import io.inspector.triage.ErrorSignatureNormalizer;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

/**
 * Rung 1: the S3 lifecycle verbs with mocked stores — transition legality (409s), episode
 * close/reopen composition, the retryable-409-never-500 conflict contract on a missed
 * state-conditional UPDATE, audit payload contents, the fail-closed compensation (ack-service
 * semantics verbatim), and the opt-in acknowledge fan-out incl. partial-failure reporting with
 * the resolve never rolled back. The column-level field semantics the mocks cannot observe
 * (seen_zero reset, regression_count untouched, resolve metadata landing on the episode) are
 * pinned against the repositories' declared native SQL below; DB-real behavior is IT territory.
 */
class IncidentLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-19T10:00:00Z");
    private static final long ID = 42L;
    private static final long EPISODE_ID = 7L;

    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final IncidentEpisodeRepository episodes = mock(IncidentEpisodeRepository.class);
    private final IncidentQueryService query = mock(IncidentQueryService.class);
    private final ErrorGroupAckService acks = mock(ErrorGroupAckService.class);
    private final AuditService audit = mock(AuditService.class);
    private final Authentication auth = mock(Authentication.class);

    private final IncidentLifecycleService service = new IncidentLifecycleService(
            incidents, episodes, query, acks, audit, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

    /* ---------------- resolve: legality + composition ---------------- */

    @Test
    void resolveFromOpenClosesTheLiveEpisodeAndAuditsTheTransition() {
        visibleIncident(IncidentState.OPEN);
        stubLiveEpisode();
        when(incidents.transitionToResolved(ID, "OPEN")).thenReturn(1);

        IncidentResolution result =
                service.resolve(ID, new ResolveIncidentRequest("gateway fixed", "OPS-1", null), auth);

        verify(incidents).transitionToResolved(ID, "OPEN");
        verify(episodes).closeEpisode(EPISODE_ID, NOW, "op1", "gateway fixed", "OPS-1");
        Map<String, Object> payload = capturePayload(IncidentLifecycleService.ACTION_RESOLVE, "gateway fixed");
        assertThat(payload)
                .containsEntry("incidentId", ID)
                .containsEntry("signatureHash", "hash-1")
                .containsEntry("algoVersion", ErrorSignatureNormalizer.ALGO_VERSION)
                .containsEntry("stateFrom", "OPEN")
                .containsEntry("stateTo", "RESOLVED")
                .containsEntry("reason", "gateway fixed")
                .containsEntry("ticketId", "OPS-1")
                .containsEntry("episodeId", EPISODE_ID);
        assertThat(result.acknowledgements()).isNull(); // not requested — omitted, not empty
    }

    @Test
    void resolveFromRegressedIsLegalAndConditionalOnTheStateSeen() {
        visibleIncident(IncidentState.REGRESSED);
        stubLiveEpisode();
        when(incidents.transitionToResolved(ID, "REGRESSED")).thenReturn(1);

        service.resolve(ID, new ResolveIncidentRequest("regression root-caused", null, null), auth);

        verify(incidents).transitionToResolved(ID, "REGRESSED");
    }

    @Test
    void resolveOnAnAlreadyResolvedIncidentIsA409AndNothingHappens() {
        visibleIncident(IncidentState.RESOLVED);

        assertThatThrownBy(() -> service.resolve(ID, new ResolveIncidentRequest("gateway fixed", null, null), auth))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("incident-already-resolved");
                });
        verify(incidents, never()).transitionToResolved(anyLong(), anyString());
        verify(audit, never()).recordConfigEvent(anyString(), anyString(), anyBoolean(), anyString(), any());
    }

    @Test
    void aRacedResolveIsARetryable409NeverA500() {
        visibleIncident(IncidentState.OPEN);
        stubLiveEpisode();
        when(incidents.transitionToResolved(ID, "OPEN")).thenReturn(0); // sampler/operator raced us

        assertThatThrownBy(() -> service.resolve(ID, new ResolveIncidentRequest("gateway fixed", null, null), auth))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT); // 409, retryable — never 500
                    assertThat(e.code()).isEqualTo("incident-state-conflict");
                });
        verify(episodes, never()).closeEpisode(anyLong(), any(), any(), any(), any());
        verify(audit, never()).recordConfigEvent(anyString(), anyString(), anyBoolean(), anyString(), any());
    }

    @Test
    void resolveShortReasonIsANamed400BeforeAnyLoad() {
        assertThatThrownBy(() -> service.resolve(ID, new ResolveIncidentRequest("meh", null, null), auth))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("reason-too-short");
                });
        verify(incidents, never()).findById(anyLong());
    }

    @Test
    void resolveOutOfScopeAnswersTheSame404AsUnknown() {
        Incident row = incident(IncidentState.OPEN);
        when(incidents.findById(ID)).thenReturn(Optional.of(row));
        when(query.projectForCaller(row, auth)).thenReturn(null); // zero readable intersection

        assertThatThrownBy(() -> service.resolve(ID, new ResolveIncidentRequest("gateway fixed", null, null), auth))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(incidents, never()).transitionToResolved(anyLong(), anyString());
    }

    @Test
    void resolveAuditFailureCompensatesTheTransitionAndRefuses503() {
        visibleIncident(IncidentState.OPEN);
        stubLiveEpisode();
        when(incidents.transitionToResolved(ID, "OPEN")).thenReturn(1);
        doThrow(new AuditUnavailableException(new RuntimeException("audit db down")))
                .when(audit)
                .recordConfigEvent(anyString(), anyString(), anyBoolean(), anyString(), any());

        assertThatThrownBy(() -> service.resolve(ID, new ResolveIncidentRequest("gateway fixed", null, null), auth))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode())
                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)); // ack-service failure semantics verbatim

        verify(episodes).reopenEpisode(EPISODE_ID); // the close is undone
        verify(incidents).revertResolve(ID, "OPEN"); // state back to what the operator saw
        verify(acks, never()).acknowledge(any(), any()); // no second action after a refused first
    }

    /* ---------------- reopen ---------------- */

    @Test
    void reopenFromResolvedRevivesTheLastEpisodeAndAudits() {
        visibleIncident(IncidentState.RESOLVED);
        stubEndedLastEpisode();
        when(incidents.transitionToReopened(ID)).thenReturn(1);

        service.reopen(ID, new ReopenIncidentRequest("resolved by mistake, still failing"), auth);

        verify(incidents).transitionToReopened(ID);
        verify(episodes).reopenEpisode(EPISODE_ID);
        Map<String, Object> payload =
                capturePayload(IncidentLifecycleService.ACTION_REOPEN, "resolved by mistake, still failing");
        assertThat(payload)
                .containsEntry("incidentId", ID)
                .containsEntry("signatureHash", "hash-1")
                .containsEntry("algoVersion", ErrorSignatureNormalizer.ALGO_VERSION)
                .containsEntry("stateFrom", "RESOLVED")
                .containsEntry("stateTo", "OPEN")
                .containsEntry("episodeId", EPISODE_ID)
                .doesNotContainKey("ticketId");
    }

    @Test
    void reopenOnANonResolvedIncidentIsA409() {
        visibleIncident(IncidentState.OPEN);

        assertThatThrownBy(() -> service.reopen(ID, new ReopenIncidentRequest("resolved by mistake, oops"), auth))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("incident-not-resolved");
                });
        verify(incidents, never()).transitionToReopened(anyLong());
    }

    @Test
    void aRacedReopenIsARetryable409() {
        visibleIncident(IncidentState.RESOLVED);
        stubEndedLastEpisode();
        when(incidents.transitionToReopened(ID)).thenReturn(0);

        assertThatThrownBy(() -> service.reopen(ID, new ReopenIncidentRequest("resolved by mistake, oops"), auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("incident-state-conflict"));
        verify(episodes, never()).reopenEpisode(anyLong());
    }

    @Test
    void reopenAuditFailureRestoresTheClosedEpisodeByteExactAndTheGateFlag() {
        Incident row = visibleIncident(IncidentState.RESOLVED);
        when(row.isSeenZeroSinceResolve()).thenReturn(true); // the gate was already armed
        IncidentEpisode last = stubEndedLastEpisode();
        when(incidents.transitionToReopened(ID)).thenReturn(1);
        doThrow(new AuditUnavailableException(new RuntimeException("audit db down")))
                .when(audit)
                .recordConfigEvent(anyString(), anyString(), anyBoolean(), anyString(), any());

        assertThatThrownBy(() -> service.reopen(ID, new ReopenIncidentRequest("resolved by mistake, oops"), auth))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        verify(episodes)
                .closeEpisode(
                        EPISODE_ID,
                        last.getEndedAt(),
                        last.getResolvedBy(),
                        last.getResolveReason(),
                        last.getTicketId());
        verify(incidents).revertReopen(ID, true); // the pre-reopen flag value, not a blind false
    }

    /* ---------------- the opt-in acknowledge fan-out ---------------- */

    @Test
    void alsoAcknowledgeComposesTheAckDoorOnceAndReportsEverySliceAcknowledged() {
        visibleIncident(IncidentState.OPEN);
        stubLiveEpisode();
        when(incidents.transitionToResolved(ID, "OPEN")).thenReturn(1);

        IncidentResolution result =
                service.resolve(ID, new ResolveIncidentRequest("gateway fixed", "OPS-1", true), auth);

        ArgumentCaptor<AcknowledgeErrorGroupRequest> ack = ArgumentCaptor.forClass(AcknowledgeErrorGroupRequest.class);
        verify(acks).acknowledge(ack.capture(), eq(auth));
        assertThat(ack.getValue().signatureHash()).isEqualTo("hash-1");
        assertThat(ack.getValue().algoVersion()).isEqualTo(ErrorSignatureNormalizer.ALGO_VERSION);
        assertThat(ack.getValue().reason()).isEqualTo("gateway fixed");
        assertThat(ack.getValue().ticketId()).isEqualTo("OPS-1");
        assertThat(ack.getValue().expiresAt()).isNull();

        // countsByEngine folds to (engine-a, order) [v3+v2 collapse, v1's zero-fill skipped] + (engine-b, pay)
        assertThat(result.acknowledgements())
                .extracting(s -> s.engineId() + "/" + s.definitionKey() + "/" + s.acknowledged())
                .containsExactly("engine-a/order/true", "engine-b/pay/true");
    }

    @Test
    void aRefusedAcknowledgeIsReportedPerSliceAndNeverRollsBackTheResolve() {
        visibleIncident(IncidentState.OPEN);
        stubLiveEpisode();
        when(incidents.transitionToResolved(ID, "OPEN")).thenReturn(1);
        when(acks.acknowledge(any(), any()))
                .thenThrow(new GuardRefusedException(
                        HttpStatus.FORBIDDEN, "rbac-denied", "missing OPERATOR on 'engine-b'. Nothing happened."));

        IncidentResolution result = service.resolve(ID, new ResolveIncidentRequest("gateway fixed", null, true), auth);

        assertThat(result.acknowledgements()).hasSize(2).allSatisfy(slice -> {
            assertThat(slice.acknowledged()).isFalse();
            assertThat(slice.code()).isEqualTo("rbac-denied");
            assertThat(slice.message()).contains("engine-b");
        });
        // the resolve stands: audited ok, never compensated
        verify(audit)
                .recordConfigEvent(eq(IncidentLifecycleService.ACTION_RESOLVE), any(), eq(true), anyString(), any());
        verify(incidents, never()).revertResolve(anyLong(), anyString());
        verify(episodes, never()).reopenEpisode(anyLong());
    }

    @Test
    void aDrainedGroupAcknowledgeRefusalIsReportedNotFatal() {
        visibleIncident(IncidentState.OPEN);
        stubLiveEpisode();
        when(incidents.transitionToResolved(ID, "OPEN")).thenReturn(1);
        when(acks.acknowledge(any(), any()))
                .thenThrow(new GuardRefusedException(
                        HttpStatus.CONFLICT, "error-group-absent", "drained since the card rendered."));

        IncidentResolution result = service.resolve(ID, new ResolveIncidentRequest("gateway fixed", null, true), auth);

        assertThat(result.acknowledgements())
                .isNotEmpty()
                .allSatisfy(slice -> assertThat(slice.code()).isEqualTo("error-group-absent"));
    }

    /* ---------------- column semantics pinned to the declared SQL ---------------- */

    /**
     * The mocks cannot observe column effects, so the field semantics the design mandates are
     * pinned against the repositories' declared native SQL: resolve resets the zero-state gate
     * and never touches regression counters; reopen goes to OPEN without incrementing
     * {@code regression_count}; the episode verbs close-with-metadata / clear-on-reopen.
     */
    @Test
    void conditionalTransitionSqlCarriesTheDesignedFieldSemantics() {
        String resolve = querySql(IncidentRepository.class, "transitionToResolved", long.class, String.class);
        assertThat(resolve)
                .contains("state = 'RESOLVED'")
                .contains("seen_zero_since_resolve = false")
                .contains("state = :expectedState")
                .contains("version = version + 1")
                .doesNotContain("regression_count");

        String reopen = querySql(IncidentRepository.class, "transitionToReopened", long.class);
        assertThat(reopen)
                .contains("state = 'OPEN'")
                .contains("seen_zero_since_resolve = false")
                .contains("state = 'RESOLVED'") // conditional on RESOLVED
                .doesNotContain("regression_count") // a human undo is NOT a regression
                .doesNotContain("last_regressed_at");

        String close = querySql(
                IncidentEpisodeRepository.class,
                "closeEpisode",
                long.class,
                Instant.class,
                String.class,
                String.class,
                String.class);
        assertThat(close)
                .contains("ended_at = :endedAt")
                .contains("resolved_by = :resolvedBy")
                .contains("resolve_reason = :resolveReason")
                .contains("ticket_id = :ticketId")
                .contains("ended_at IS NULL"); // only a live episode closes

        String revive = querySql(IncidentEpisodeRepository.class, "reopenEpisode", long.class);
        assertThat(revive)
                .contains("ended_at = NULL")
                .contains("resolved_by = NULL")
                .contains("resolve_reason = NULL")
                .contains("ticket_id = NULL");
    }

    /* ---------------- fixtures ---------------- */

    private static String querySql(Class<?> repository, String method, Class<?>... params) {
        try {
            Method m = repository.getMethod(method, params);
            return m.getAnnotation(Query.class).value();
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private Incident visibleIncident(IncidentState state) {
        Incident row = incident(state);
        when(incidents.findById(ID)).thenReturn(Optional.of(row));
        when(query.projectForCaller(row, auth)).thenReturn(summary(state));
        when(auth.getName()).thenReturn("op1");
        return row;
    }

    private static IncidentSummary summary(IncidentState state) {
        return new IncidentSummary(
                ID,
                "hash-1",
                1,
                true,
                null,
                "m",
                "m",
                state.name(),
                NOW,
                NOW,
                false,
                7,
                false,
                Map.of(),
                false,
                0,
                null);
    }

    private static Incident incident(IncidentState state) {
        Incident row = mock(Incident.class);
        when(row.getId()).thenReturn(ID);
        when(row.getSignatureHash()).thenReturn("hash-1");
        when(row.getAlgoVersion()).thenReturn(ErrorSignatureNormalizer.ALGO_VERSION);
        when(row.getState()).thenReturn(state);
        when(row.getCountsByEngine())
                .thenReturn(
                        "{\"engine-a\":{\"order:v3\":4,\"order:v2\":1,\"order:v1\":0},\"engine-b\":{\"pay:v1\":2}}");
        return row;
    }

    private IncidentEpisode stubLiveEpisode() {
        IncidentEpisode episode = mock(IncidentEpisode.class);
        when(episode.getId()).thenReturn(EPISODE_ID);
        when(episodes.findFirstByIncidentIdAndEndedAtIsNullOrderByStartedAtDesc(ID))
                .thenReturn(Optional.of(episode));
        return episode;
    }

    private IncidentEpisode stubEndedLastEpisode() {
        IncidentEpisode episode = mock(IncidentEpisode.class);
        when(episode.getId()).thenReturn(EPISODE_ID);
        when(episode.getEndedAt()).thenReturn(NOW.minusSeconds(3600));
        when(episode.getResolvedBy()).thenReturn("op0");
        when(episode.getResolveReason()).thenReturn("thought it was the gateway");
        when(episode.getTicketId()).thenReturn("OPS-0");
        when(episodes.findFirstByIncidentIdOrderByStartedAtDesc(ID)).thenReturn(Optional.of(episode));
        return episode;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturePayload(String action, String reason) {
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(audit).recordConfigEvent(eq(action), eq("op1"), eq(true), eq(reason), payload.capture());
        return payload.getValue();
    }
}
