package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.bulk.BulkJob;
import io.inspector.bulk.BulkJobItem;
import io.inspector.bulk.BulkJobItemRepository;
import io.inspector.bulk.BulkJobRepository;
import io.inspector.security.ReadScopeGate;
import io.inspector.support.NoDbTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
 * Rung 3 — the R-SAFE-17 scope projection on the bulk-job list/detail reads, over REAL HTTP
 * (issue #296). Same doctrine as {@code IncidentScopeApiSpringTest} / {@code
 * AuditScopeApiSpringTest}: {@link ReadScopeGate} is overridden to emulate a genuinely
 * per-engine-scoped session (the dev basic-auth ladder is otherwise global-scoped, a no-op for
 * enforcement) — this is the MANDATORY enforcement-ON slice for the bulk surface; every other
 * bulk test in this codebase runs with scoping effectively off and would pass unchanged either
 * way. {@code BulkJobRepository}/{@code BulkJobItemRepository} are mocked (via {@code
 * NoDbTestSupport}) to return whichever rows a real scoped query WOULD have returned — proving
 * the wire contract (right query called, right rows/items rendered, existence never leaked),
 * not the JPQL's own SQL correctness.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class BulkScopeApiSpringTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    BulkJobRepository jobs;

    @Autowired
    BulkJobItemRepository items;

    @MockitoBean
    ReadScopeGate gate;

    @AfterEach
    void resetMocks() {
        reset(jobs, items, gate);
    }

    private TestRestTemplate viewer() {
        return rest.withBasicAuth("viewer", "dev");
    }

    private static BulkJob job(UUID id, int totalItems) {
        return new BulkJob(
                id,
                "k.meier",
                Instant.parse("2026-07-20T09:00:00Z"),
                "retry-job",
                "ops-4711 incident replay",
                "OPS-1",
                totalItems,
                null,
                BulkJob.ScopeKind.SELECTION,
                null);
    }

    private static BulkJobItem item(UUID jobId, int ordinal, String engineId, String instanceId) {
        BulkJobItem row = new BulkJobItem(jobId, ordinal, engineId, instanceId, null, BulkJobItem.State.pending);
        row.settle(
                BulkJobItem.State.ok,
                "job moved back to the executable queue",
                null,
                Instant.parse("2026-07-20T09:01:00Z"));
        return row;
    }

    @Test
    void scopedViewerSeesOnlyInScopeItemsAndARecomputedTotal_neverTheUnscopedQuery() throws Exception {
        UUID id = UUID.randomUUID();
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(Set.of("probe-dev"));
        // The fleet-wide job actually spans 2 engines; only probe-dev is readable.
        when(jobs.findRecentByReadableEngineIds(eq(Set.of("probe-dev")), any())).thenReturn(List.of(job(id, 2)));
        when(items.findByJobIdAndEngineIdInOrderByOrdinal(eq(id), eq(Set.of("probe-dev"))))
                .thenReturn(List.of(item(id, 0, "probe-dev", "i-1")));

        ResponseEntity<String> res = viewer().getForEntity("/api/bulk", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(res.getBody());
        assertThat(body).hasSize(1);
        // recomputed from the visible items — never the fleet-wide 2 (TriageScopeProjector doctrine)
        assertThat(body.get(0).path("totalItems").asInt()).isEqualTo(1);
        verify(jobs, never()).findAllByOrderBySubmittedAtDesc(any());
    }

    @Test
    void aJobTouchingNoReadableEngineIsOmittedFromTheList() {
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(Set.of("probe-dev"));
        when(jobs.findRecentByReadableEngineIds(eq(Set.of("probe-dev")), any())).thenReturn(List.of());

        ResponseEntity<String> res = viewer().getForEntity("/api/bulk", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo("[]");
    }

    @Test
    void zeroIntersectionDetailIsIndistinguishableFromUnknown() {
        UUID id = UUID.randomUUID();
        UUID unknownId = UUID.randomUUID();
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(Set.of("probe-dev"));
        when(jobs.findById(id)).thenReturn(Optional.of(job(id, 1)));
        when(jobs.findById(unknownId)).thenReturn(Optional.empty());
        // The job's only item lives on an engine outside the caller's scope.
        when(items.findByJobIdAndEngineIdInOrderByOrdinal(eq(id), eq(Set.of("probe-dev"))))
                .thenReturn(List.of());

        ResponseEntity<String> existing = viewer().getForEntity("/api/bulk/" + id, String.class);
        ResponseEntity<String> unknown = viewer().getForEntity("/api/bulk/" + unknownId, String.class);

        assertThat(existing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aFullyInScopeDetailShowsEveryItem() throws Exception {
        UUID id = UUID.randomUUID();
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(Set.of("probe-dev"));
        when(jobs.findById(id)).thenReturn(Optional.of(job(id, 1)));
        when(items.findByJobIdAndEngineIdInOrderByOrdinal(eq(id), eq(Set.of("probe-dev"))))
                .thenReturn(List.of(item(id, 0, "probe-dev", "i-1")));

        ResponseEntity<String> res = viewer().getForEntity("/api/bulk/" + id, String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(res.getBody());
        assertThat(body.path("items")).hasSize(1);
        assertThat(body.path("items").get(0).path("engineId").asText()).isEqualTo("probe-dev");
        assertThat(body.path("items").get(0).path("instanceId").asText()).isEqualTo("i-1");
    }

    @Test
    void anUnscopedViewerIsUnaffected_legacyFleetWideListStands() {
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(null);
        UUID id = UUID.randomUUID();
        when(jobs.findAllByOrderBySubmittedAtDesc(any())).thenReturn(List.of(job(id, 1)));
        when(items.findByJobIdOrderByOrdinal(id)).thenReturn(List.of(item(id, 0, "probe-prod", "i-9")));

        ResponseEntity<String> res = viewer().getForEntity("/api/bulk", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Unscoped list reads never include items (tallies only) — the legacy call path is what
        // this test proves, via the interaction verifications below.
        assertThat(res.getBody()).contains("\"id\":\"" + id + "\"");
        verify(jobs, never()).findRecentByReadableEngineIds(any(), any());
        verify(items, never()).findByJobIdAndEngineIdInOrderByOrdinal(any(), any());
    }
}
