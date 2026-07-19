package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditEntryRepository;
import io.inspector.dto.ErrorGroup;
import io.inspector.dto.TriageDashboardResponse;
import io.inspector.incident.Incident;
import io.inspector.incident.IncidentEpisode;
import io.inspector.incident.IncidentEpisodeRepository;
import io.inspector.incident.IncidentRepository;
import io.inspector.incident.IncidentState;
import io.inspector.security.ReadScopeGate;
import io.inspector.support.NoDbTestSupport;
import io.inspector.triage.TriageService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Rung 3 — the S3 lifecycle verbs over real HTTP (dev basic-auth ladder, `test` registry twins
 * on closed ports, ledger + audit repositories auto-mocked by {@link NoDbTestSupport}).
 *
 * <p>{@link ReadScopeGate} is overridden exactly as in {@link IncidentScopeApiSpringTest}
 * (default {@code null} = enforcement off) so one context proves both the plain-OPERATOR happy
 * paths and the scoped-operator 404. {@link TriageService} is overridden so the opt-in
 * acknowledge can meet a LIVE group (the dead twin engines would otherwise honestly answer
 * {@code error-group-absent}): the ack door's server-side group resolution runs for real
 * against the stubbed aggregation, its per-engine RBAC re-check runs against the real
 * {@code rbac} bean, and its config event lands in the (mocked) audit store — proving the
 * two-distinct-audit-rows contract end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class IncidentLifecycleApiSpringTest {

    private static final Instant LAST_SEEN = Instant.parse("2026-07-19T09:00:00Z");
    private static final String RESOLVE = "/api/incidents/42/resolve";
    private static final String REOPEN = "/api/incidents/42/reopen";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    IncidentRepository incidents;

    @Autowired
    IncidentEpisodeRepository episodes;

    @Autowired
    AuditEntryRepository auditEntries;

    @MockitoBean
    ReadScopeGate gate;

    @MockitoBean
    TriageService triage;

    @BeforeEach
    void defaults() {
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(null); // enforcement off
        when(triage.dashboard(anyBoolean())).thenReturn(emptyDashboard()); // dead engines, no live groups
    }

    @AfterEach
    void resetMocks() {
        reset(incidents, episodes, auditEntries);
    }

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    /* ---------------- doors ---------------- */

    @Test
    void anonymousAndSubOperatorCallersAreShutAtTheDoor() {
        Map<String, Object> body = Map.of("reason", "gateway restarted, drained");
        // cookie-less anonymous POST dies at the CSRF gate — still shut
        assertThat(rest.postForEntity(RESOLVE, body, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        for (String user : List.of("viewer", "responder")) {
            assertThat(as(user).postForEntity(RESOLVE, body, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(as(user).postForEntity(REOPEN, body, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        verify(incidents, never()).findById(anyLong());
    }

    @Test
    void shortReasonIsANamed400OnBothVerbs() throws Exception {
        for (String path : List.of(RESOLVE, REOPEN)) {
            ResponseEntity<String> res = as("operator").postForEntity(path, Map.of("reason", "meh"), String.class);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(mapper.readTree(res.getBody()).path("code").asText()).isEqualTo("reason-too-short");
        }
        verify(incidents, never()).transitionToResolved(anyLong(), anyString());
    }

    /* ---------------- resolve ---------------- */

    @Test
    void operatorResolvesAnOpenIncident_episodeClosedAndConfigEventWritten() throws Exception {
        Incident open = row(IncidentState.OPEN);
        Incident resolved = row(IncidentState.RESOLVED);
        when(incidents.findById(42L)).thenReturn(Optional.of(open), Optional.of(resolved));
        when(incidents.transitionToResolved(42L, "OPEN")).thenReturn(1);
        stubLiveEpisode();

        ResponseEntity<String> res = as("operator")
                .postForEntity(
                        RESOLVE, Map.of("reason", "gateway restarted, drained", "ticketId", "OPS-77"), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(res.getBody());
        assertThat(body.path("incident").path("state").asText()).isEqualTo("RESOLVED");
        assertThat(body.has("acknowledgements")).isFalse(); // not requested — omitted

        verify(episodes)
                .closeEpisode(
                        eq(7L), any(Instant.class), eq("operator"), eq("gateway restarted, drained"), eq("OPS-77"));
        AuditEntry entry = capturedConfigEvents().get(0);
        assertThat(entry.getAction()).isEqualTo("incident-resolve");
        assertThat(entry.getReason()).isEqualTo("gateway restarted, drained");
        assertThat(entry.getPayload())
                .contains("\"stateFrom\":\"OPEN\"")
                .contains("\"stateTo\":\"RESOLVED\"")
                .contains("\"signatureHash\":\"hash-1\"")
                .contains("\"episodeId\":7");
    }

    @Test
    void doubleResolveIsANamed409() throws Exception {
        Incident alreadyResolved = row(IncidentState.RESOLVED);
        when(incidents.findById(42L)).thenReturn(Optional.of(alreadyResolved));

        ResponseEntity<String> res =
                as("operator").postForEntity(RESOLVE, Map.of("reason", "gateway restarted, drained"), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mapper.readTree(res.getBody()).path("code").asText()).isEqualTo("incident-already-resolved");
        verify(incidents, never()).transitionToResolved(anyLong(), anyString());
    }

    @Test
    void aRacedStateTransitionIsARetryable409NeverA500() throws Exception {
        Incident open = row(IncidentState.OPEN);
        when(incidents.findById(42L)).thenReturn(Optional.of(open));
        // transitionToResolved left unstubbed → 0 rows hit, exactly a lost optimistic race

        ResponseEntity<String> res =
                as("operator").postForEntity(RESOLVE, Map.of("reason", "gateway restarted, drained"), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = mapper.readTree(res.getBody());
        assertThat(problem.path("code").asText()).isEqualTo("incident-state-conflict");
        assertThat(problem.path("detail").asText()).contains("retry");
    }

    /* ---------------- reopen ---------------- */

    @Test
    void operatorReopensAResolvedIncident_lastEpisodeLiveAgain() throws Exception {
        Incident resolved = row(IncidentState.RESOLVED);
        Incident reopened = row(IncidentState.OPEN);
        when(incidents.findById(42L)).thenReturn(Optional.of(resolved), Optional.of(reopened));
        when(incidents.transitionToReopened(42L)).thenReturn(1);
        IncidentEpisode ended = mock(IncidentEpisode.class);
        when(ended.getId()).thenReturn(7L);
        when(ended.getEndedAt()).thenReturn(LAST_SEEN);
        when(episodes.findFirstByIncidentIdOrderByStartedAtDesc(42L)).thenReturn(Optional.of(ended));

        ResponseEntity<String> res = as("operator")
                .postForEntity(REOPEN, Map.of("reason", "resolved by mistake, still failing"), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(res.getBody()).path("state").asText()).isEqualTo("OPEN");
        verify(episodes).reopenEpisode(7L);
        AuditEntry entry = capturedConfigEvents().get(0);
        assertThat(entry.getAction()).isEqualTo("incident-reopen");
        assertThat(entry.getPayload()).contains("\"stateFrom\":\"RESOLVED\"").contains("\"stateTo\":\"OPEN\"");
    }

    @Test
    void reopenOfANonResolvedIncidentIsANamed409() throws Exception {
        Incident open = row(IncidentState.OPEN);
        when(incidents.findById(42L)).thenReturn(Optional.of(open));

        ResponseEntity<String> res =
                as("operator").postForEntity(REOPEN, Map.of("reason", "resolved by mistake, oops"), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mapper.readTree(res.getBody()).path("code").asText()).isEqualTo("incident-not-resolved");
    }

    /* ---------------- the opt-in second action ---------------- */

    @Test
    void alsoAcknowledgeWritesTwoDistinctConfigEvents() throws Exception {
        Incident open = row(IncidentState.OPEN);
        Incident resolved = row(IncidentState.RESOLVED);
        when(incidents.findById(42L)).thenReturn(Optional.of(open), Optional.of(resolved));
        when(incidents.transitionToResolved(42L, "OPEN")).thenReturn(1);
        stubLiveEpisode();
        // the ack door resolves its slices from the LIVE aggregation — give it a matching group
        when(triage.dashboard(anyBoolean())).thenReturn(dashboardWith(liveGroup()));

        ResponseEntity<String> res = as("operator")
                .postForEntity(
                        RESOLVE, Map.of("reason", "gateway restarted, drained", "alsoAcknowledge", true), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode acks = mapper.readTree(res.getBody()).path("acknowledgements");
        assertThat(acks.isArray()).isTrue();
        assertThat(acks).hasSize(1);
        assertThat(acks.get(0).path("engineId").asText()).isEqualTo("probe-dev");
        assertThat(acks.get(0).path("definitionKey").asText()).isEqualTo("order");
        assertThat(acks.get(0).path("acknowledged").asBoolean()).isTrue();

        List<AuditEntry> events = capturedConfigEvents();
        assertThat(events).hasSize(2); // the resolve AND the ack — separately audited
        assertThat(events.stream().map(AuditEntry::getAction))
                .containsExactly("incident-resolve", "error-group-acknowledge");
    }

    /* ---------------- scope ---------------- */

    @Test
    void aScopedOperatorCannotResolveAnIncidentTheyCannotSee() throws Exception {
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(Set.of("probe-dev"));
        Incident foreign = row(IncidentState.OPEN, "{\"probe-prod\":{\"pay:v1\":8}}");
        when(incidents.findById(42L)).thenReturn(Optional.of(foreign));

        ResponseEntity<String> outOfScope =
                as("operator").postForEntity(RESOLVE, Map.of("reason", "gateway restarted, drained"), String.class);
        ResponseEntity<String> unknown = as("operator")
                .postForEntity(
                        "/api/incidents/999/resolve", Map.of("reason", "gateway restarted, drained"), String.class);

        // out-of-scope and truly-absent answer identically — no existence leak, no mutation
        assertThat(outOfScope.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(mapper.readTree(outOfScope.getBody()).path("code").asText())
                .isEqualTo(mapper.readTree(unknown.getBody()).path("code").asText());
        verify(incidents, never()).transitionToResolved(anyLong(), anyString());
    }

    /* ---------------- fixtures ---------------- */

    private static Incident row(IncidentState state) {
        return row(state, "{\"probe-dev\":{\"order:v3\":7}}");
    }

    private static Incident row(IncidentState state, String countsJson) {
        Incident row = mock(Incident.class);
        when(row.getId()).thenReturn(42L);
        when(row.getSignatureHash()).thenReturn("hash-1");
        when(row.getAlgoVersion()).thenReturn(1);
        when(row.getExceptionClass()).thenReturn("java.net.SocketTimeoutException");
        when(row.getNormalizedMessage()).thenReturn("timeout after # ms");
        when(row.getSampleRawMessage()).thenReturn("timeout after 5000 ms");
        when(row.getState()).thenReturn(state);
        when(row.getFirstSeen()).thenReturn(LAST_SEEN.minusSeconds(86400));
        when(row.getLastSeen()).thenReturn(LAST_SEEN);
        when(row.getLastTotal()).thenReturn(7L);
        when(row.isLastTruncated()).thenReturn(false);
        when(row.getCountsByEngine()).thenReturn(countsJson);
        when(row.getRegressionCount()).thenReturn(0);
        when(row.getLastRegressedAt()).thenReturn(null);
        return row;
    }

    private void stubLiveEpisode() {
        IncidentEpisode live = mock(IncidentEpisode.class);
        when(live.getId()).thenReturn(7L);
        when(episodes.findFirstByIncidentIdAndEndedAtIsNullOrderByStartedAtDesc(42L))
                .thenReturn(Optional.of(live));
    }

    private static ErrorGroup liveGroup() {
        return new ErrorGroup(
                "hash-1",
                1,
                "java.net.SocketTimeoutException",
                "timeout after # ms",
                "timeout after 5000 ms",
                7,
                7,
                0,
                Map.of("probe-dev", Map.of("order:v3", 7L)));
    }

    private static TriageDashboardResponse emptyDashboard() {
        return dashboardWith();
    }

    private static TriageDashboardResponse dashboardWith(ErrorGroup... groups) {
        return new TriageDashboardResponse(
                LAST_SEEN.toString(), List.of(), Map.of(), Map.of(), List.of(groups), Map.of());
    }

    private List<AuditEntry> capturedConfigEvents() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditEntries, times(mockingDetailsCount())).saveAndFlush(captor.capture());
        return captor.getAllValues();
    }

    /** How many audit INSERTs actually happened — lets one helper serve 1- and 2-event tests. */
    private int mockingDetailsCount() {
        return (int) org.mockito.Mockito.mockingDetails(auditEntries).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("saveAndFlush"))
                .count();
    }
}
