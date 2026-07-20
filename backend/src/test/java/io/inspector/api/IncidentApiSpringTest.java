package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.incident.Incident;
import io.inspector.incident.IncidentEpisode;
import io.inspector.incident.IncidentEpisodeRepository;
import io.inspector.incident.IncidentRepository;
import io.inspector.incident.IncidentState;
import io.inspector.support.NoDbTestSupport;
import io.inspector.triage.ErrorSignatureNormalizer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Rung 3 — the S2 incident read doors over real HTTP (dev basic-auth ladder, `test` registry
 * twins on closed ports, ledger repositories auto-mocked by {@link NoDbTestSupport}).
 *
 * <p>This context runs with {@code inspector.incidents.enabled=false} (the `test` profile),
 * which by itself proves the ledger's INGESTION flag does not gate the READ path: the
 * controller and {@code IncidentQueryService} wire and answer without the
 * {@code IncidentLedgerService} listener bean existing at all.
 *
 * <p>The live join exercises the REAL dashboard path against the dead twin engines — zero live
 * error groups, so {@code live} is honestly omitted (NON_NULL), matching the drained case.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class IncidentApiSpringTest {

    /** Comfortably past the 24h quiet window relative to the context's REAL clock. */
    private static final Instant LAST_SEEN = Instant.now().minusSeconds(3 * 86400);

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    IncidentRepository incidents;

    @Autowired
    IncidentEpisodeRepository episodes;

    @AfterEach
    void resetMocks() {
        reset(incidents, episodes);
    }

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    @Test
    void anonymousReadsAreShut() {
        assertThat(rest.getForEntity("/api/incidents", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/api/incidents/1", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void viewerListsTheLedgerWithDerivedFields() throws Exception {
        Incident row = incidentRow();
        when(incidents.findAllByOrderByLastSeenDesc()).thenReturn(List.of(row));

        ResponseEntity<String> res = as("viewer").getForEntity("/api/incidents", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(res.getBody());
        assertThat(body).hasSize(1);
        JsonNode item = body.get(0);
        assertThat(item.get("signatureHash").asText()).isEqualTo("hash-1");
        assertThat(item.get("state").asText()).isEqualTo("OPEN");
        assertThat(item.get("currentGeneration").asBoolean()).isTrue();
        assertThat(item.get("quiet").asBoolean()).isTrue(); // LAST_SEEN is days in the past
        assertThat(item.get("partial").asBoolean()).isFalse(); // enforcement off in this profile
        assertThat(item.get("lastTotal").asLong()).isEqualTo(7);
        assertThat(item.path("countsByEngine")
                        .path("probe-dev")
                        .path("order:v3")
                        .asLong())
                .isEqualTo(7);
    }

    @Test
    void viewerReadsTheDetailWithEpisodesAndSeries() throws Exception {
        Incident row = incidentRow();
        IncidentEpisode ended = endedEpisode();
        when(incidents.findById(42L)).thenReturn(Optional.of(row));
        when(episodes.findByIncidentIdOrderByStartedAtDesc(42L)).thenReturn(List.of(ended));

        ResponseEntity<String> res = as("viewer").getForEntity("/api/incidents/42?window=48", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(res.getBody());
        assertThat(body.path("incident").path("signatureHash").asText()).isEqualTo("hash-1");
        assertThat(body.path("seriesWindow").asText()).isEqualTo("PT48H");
        assertThat(body.path("series").isArray()).isTrue();
        assertThat(body.path("episodes")).hasSize(1);
        JsonNode episode = body.path("episodes").get(0);
        assertThat(episode.path("startState").asText()).isEqualTo("OPEN");
        assertThat(episode.path("durationSeconds").asLong()).isEqualTo(3600L);
        // the dead twin engines aggregate to zero live groups → `live` honestly omitted
        assertThat(body.has("live")).isFalse();
    }

    @Test
    void unknownIncidentIsAProblemDetail404() throws Exception {
        ResponseEntity<String> res = as("viewer").getForEntity("/api/incidents/999", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode problem = mapper.readTree(res.getBody());
        assertThat(problem.path("code").asText()).isEqualTo("not-found");
    }

    @Test
    void anInvalidStateFilterIsAProblemDetail400() throws Exception {
        ResponseEntity<String> res = as("viewer").getForEntity("/api/incidents?state=EXPLODED", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode problem = mapper.readTree(res.getBody());
        assertThat(problem.path("detail").asText()).contains("OPEN, RESOLVED or REGRESSED");
    }

    @Test
    void aCaseInsensitiveStateFilterIsAccepted() {
        when(incidents.findByStateOrderByLastSeenDesc(IncidentState.RESOLVED)).thenReturn(List.of());

        assertThat(as("viewer")
                        .getForEntity("/api/incidents?state=resolved", String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** Mocked persisted row (the setter-less-entity convention of the ledger tests). */
    static Incident incidentRow() {
        Incident row = mock(Incident.class);
        when(row.getId()).thenReturn(42L);
        when(row.getSignatureHash()).thenReturn("hash-1");
        when(row.getAlgoVersion()).thenReturn(ErrorSignatureNormalizer.ALGO_VERSION);
        when(row.getExceptionClass()).thenReturn("java.net.SocketTimeoutException");
        when(row.getNormalizedMessage()).thenReturn("timeout after # ms");
        when(row.getSampleRawMessage()).thenReturn("timeout after 5000 ms");
        when(row.getState()).thenReturn(IncidentState.OPEN);
        when(row.getFirstSeen()).thenReturn(LAST_SEEN.minusSeconds(86400));
        when(row.getLastSeen()).thenReturn(LAST_SEEN);
        when(row.getLastTotal()).thenReturn(7L);
        when(row.isLastTruncated()).thenReturn(false);
        when(row.getCountsByEngine()).thenReturn("{\"probe-dev\":{\"order:v3\":7}}");
        when(row.getRegressionCount()).thenReturn(0);
        when(row.getLastRegressedAt()).thenReturn(null);
        return row;
    }

    private static IncidentEpisode endedEpisode() {
        IncidentEpisode episode = mock(IncidentEpisode.class);
        when(episode.getId()).thenReturn(7L);
        when(episode.getStartState()).thenReturn(IncidentState.OPEN);
        when(episode.getStartedAt()).thenReturn(LAST_SEEN.minusSeconds(3600));
        when(episode.getEndedAt()).thenReturn(LAST_SEEN);
        when(episode.getResolvedBy()).thenReturn("op1");
        when(episode.getResolveReason()).thenReturn("gateway restarted, drained");
        when(episode.getTicketId()).thenReturn(null);
        when(episode.getPeakTotal()).thenReturn(9L);
        return episode;
    }
}
