package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.incident.Incident;
import io.inspector.incident.IncidentRepository;
import io.inspector.incident.IncidentState;
import io.inspector.security.ReadScopeGate;
import io.inspector.support.NoDbTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
 * Rung 3 — the R-SAFE-17 scope projection on the incident read doors over real HTTP. The dev
 * basic-auth ladder is inherently global-scoped (enforcement is a no-op for it), so the
 * {@link ReadScopeGate} seam is overridden to emulate a genuinely per-engine-scoped session —
 * the real grant→readable-set derivation is proven separately by {@code ReadScopeGateTest};
 * what THIS class proves is the wire contract under a narrowed set: filtered breakdown +
 * recomputed partial total, foreign incidents omitted from the list, and the zero-intersection
 * detail answering the SAME 404 as an unknown id (never a 403 — existence is not leaked).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class IncidentScopeApiSpringTest {

    private static final Instant LAST_SEEN = Instant.parse("2026-07-18T09:00:00Z");

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    IncidentRepository incidents;

    @MockitoBean
    ReadScopeGate gate;

    @AfterEach
    void resetMocks() {
        reset(incidents);
    }

    private TestRestTemplate viewer() {
        return rest.withBasicAuth("viewer", "dev");
    }

    @Test
    void aScopedViewerSeesFilteredBreakdownsAndNoForeignIncidents() throws Exception {
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(Set.of("probe-dev"));
        // spanning: probe-dev 4 + probe-prod 8 (fleet 12); foreign: probe-prod only
        Incident spanning = row(1L, "spanning", "{\"probe-dev\":{\"order:v3\":4},\"probe-prod\":{\"order:v3\":8}}", 12);
        Incident foreign = row(2L, "foreign", "{\"probe-prod\":{\"pay:v1\":8}}", 8);
        when(incidents.findAllByOrderByLastSeenDesc()).thenReturn(List.of(spanning, foreign));

        ResponseEntity<String> res = viewer().getForEntity("/api/incidents", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(res.getBody());
        assertThat(body).hasSize(1); // the foreign incident is OMITTED, not blanked
        JsonNode item = body.get(0);
        assertThat(item.get("signatureHash").asText()).isEqualTo("spanning");
        assertThat(item.get("partial").asBoolean()).isTrue();
        assertThat(item.get("lastTotal").asLong()).isEqualTo(4); // recomputed, never the fleet 12
        assertThat(item.path("countsByEngine").has("probe-prod")).isFalse();
        assertThat(item.path("countsByEngine")
                        .path("probe-dev")
                        .path("order:v3")
                        .asLong())
                .isEqualTo(4);
    }

    @Test
    void zeroIntersectionDetailIsIndistinguishableFromUnknown() throws Exception {
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(Set.of("probe-dev"));
        Incident foreign = row(2L, "foreign", "{\"probe-prod\":{\"pay:v1\":8}}", 8);
        when(incidents.findById(2L)).thenReturn(Optional.of(foreign));

        ResponseEntity<String> existing = viewer().getForEntity("/api/incidents/2", String.class);
        ResponseEntity<String> unknown = viewer().getForEntity("/api/incidents/999", String.class);

        // out-of-scope and truly-absent answer identically (404, same code) — no existence leak
        assertThat(existing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(mapper.readTree(existing.getBody()).path("code").asText())
                .isEqualTo(mapper.readTree(unknown.getBody()).path("code").asText());
    }

    @Test
    void aFullyInScopeDetailStaysReadable() throws Exception {
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(Set.of("probe-dev"));
        Incident mine = row(3L, "mine", "{\"probe-dev\":{\"order:v3\":4}}", 4);
        when(incidents.findById(3L)).thenReturn(Optional.of(mine));

        ResponseEntity<String> res = viewer().getForEntity("/api/incidents/3", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode incident = mapper.readTree(res.getBody()).path("incident");
        assertThat(incident.path("partial").asBoolean()).isFalse();
        assertThat(incident.path("lastTotal").asLong()).isEqualTo(4); // fleet total carried verbatim
    }

    private static Incident row(long id, String hash, String countsJson, long lastTotal) {
        Incident row = mock(Incident.class);
        when(row.getId()).thenReturn(id);
        when(row.getSignatureHash()).thenReturn(hash);
        when(row.getAlgoVersion()).thenReturn(1);
        when(row.getExceptionClass()).thenReturn("java.net.SocketTimeoutException");
        when(row.getNormalizedMessage()).thenReturn("timeout after # ms");
        when(row.getSampleRawMessage()).thenReturn("timeout after 5000 ms");
        when(row.getState()).thenReturn(IncidentState.OPEN);
        when(row.getFirstSeen()).thenReturn(LAST_SEEN.minusSeconds(86400));
        when(row.getLastSeen()).thenReturn(LAST_SEEN);
        when(row.getLastTotal()).thenReturn(lastTotal);
        when(row.isLastTruncated()).thenReturn(false);
        when(row.getCountsByEngine()).thenReturn(countsJson);
        when(row.getRegressionCount()).thenReturn(0);
        when(row.getLastRegressedAt()).thenReturn(null);
        return row;
    }
}
