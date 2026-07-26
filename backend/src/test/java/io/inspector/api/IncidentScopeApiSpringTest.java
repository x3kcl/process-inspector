package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.AuditEntryRepository;
import io.inspector.bulk.BulkJob;
import io.inspector.bulk.BulkJobItem;
import io.inspector.bulk.BulkJobItemRepository;
import io.inspector.bulk.BulkJobRepository;
import io.inspector.incident.Incident;
import io.inspector.incident.IncidentRepository;
import io.inspector.incident.IncidentState;
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

    @Autowired
    AuditEntryRepository audits;

    @Autowired
    BulkJobRepository jobs;

    @Autowired
    BulkJobItemRepository items;

    @MockitoBean
    ReadScopeGate gate;

    @AfterEach
    void resetMocks() {
        reset(incidents, audits, jobs, items);
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
        when(incidents.findAllByOrderByLastSeenDesc(any())).thenReturn(List.of(spanning, foreign));

        ResponseEntity<String> res = viewer().getForEntity("/api/incidents", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(res.getBody());
        assertThat(body.path("items")).hasSize(1); // the foreign incident is OMITTED, not blanked
        JsonNode item = body.path("items").get(0);
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

    /* ---------------- related-bulk-jobs join scoping (S5, R-SAFE-17, issue #329) ---------------- */

    @Test
    void relatedBulkJobsOmitsAJobTouchingNoReadableEngine() throws Exception {
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(Set.of("probe-dev"));
        Incident mine = row(4L, "mine-4", "{\"probe-dev\":{\"order:v3\":4}}", 4);
        when(incidents.findById(4L)).thenReturn(Optional.of(mine));
        UUID jobId = UUID.randomUUID();
        when(audits.findRecentErrorClassBulkJobIds("mine-4", "1", 10)).thenReturn(List.of(jobId.toString()));
        when(jobs.findAllById(List.of(jobId))).thenReturn(List.of(bulkJob(jobId, 2)));
        // the job's items are ALL on an engine outside the caller's scope
        when(items.findByJobIdInAndEngineIdIn(any(), eq(Set.of("probe-dev")))).thenReturn(List.of());

        ResponseEntity<String> res = viewer().getForEntity("/api/incidents/4", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(res.getBody());
        assertThat(body.path("relatedBulkJobs")).isEmpty(); // omitted entirely, never a hidden item set
    }

    @Test
    void relatedBulkJobsNarrowsASpanningJobWithRecomputedTotalsAndTallies() throws Exception {
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(Set.of("probe-dev"));
        Incident mine = row(5L, "mine-5", "{\"probe-dev\":{\"order:v3\":4}}", 4);
        when(incidents.findById(5L)).thenReturn(Optional.of(mine));
        UUID jobId = UUID.randomUUID();
        // fleet-wide the job has 2 items (probe-dev + probe-prod); only probe-dev is readable
        List<BulkJobItem> visible = List.of(bulkItem(jobId, 0, "probe-dev", "i-1"));
        when(audits.findRecentErrorClassBulkJobIds("mine-5", "1", 10)).thenReturn(List.of(jobId.toString()));
        when(jobs.findAllById(List.of(jobId))).thenReturn(List.of(bulkJob(jobId, 2)));
        when(items.findByJobIdInAndEngineIdIn(any(), eq(Set.of("probe-dev")))).thenReturn(visible);

        ResponseEntity<String> res = viewer().getForEntity("/api/incidents/5", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode relatedJob =
                mapper.readTree(res.getBody()).path("relatedBulkJobs").get(0);
        // recomputed from the visible item only — never the fleet-wide 2 the job actually spans
        // (the sharpest leak issue #329 closes: the OTHER engine's item never reaches this caller)
        assertThat(relatedJob.path("totalItems").asInt()).isEqualTo(1);
        assertThat(relatedJob.path("tallies").path("ok").asInt()).isEqualTo(1);
    }

    @Test
    void anUnscopedCallerSeesTheFullRelatedBulkJobsJoin() throws Exception {
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(null);
        Incident mine = row(6L, "mine-6", "{\"probe-dev\":{\"order:v3\":4}}", 4);
        when(incidents.findById(6L)).thenReturn(Optional.of(mine));
        UUID jobId = UUID.randomUUID();
        List<BulkJobItem> allItems =
                List.of(bulkItem(jobId, 0, "probe-dev", "i-1"), bulkItem(jobId, 1, "probe-prod", "i-2"));
        when(audits.findRecentErrorClassBulkJobIds("mine-6", "1", 10)).thenReturn(List.of(jobId.toString()));
        when(jobs.findAllById(List.of(jobId))).thenReturn(List.of(bulkJob(jobId, 2)));
        when(items.findByJobIdIn(any())).thenReturn(allItems);

        ResponseEntity<String> res = viewer().getForEntity("/api/incidents/6", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode relatedJob =
                mapper.readTree(res.getBody()).path("relatedBulkJobs").get(0);
        assertThat(relatedJob.path("totalItems").asInt()).isEqualTo(2);
        verify(items, never()).findByJobIdInAndEngineIdIn(any(), any());
    }

    private static BulkJob bulkJob(UUID id, int totalItems) {
        return new BulkJob(
                id,
                "k.meier",
                Instant.parse("2026-07-20T09:00:00Z"),
                "retry-job",
                "ops-4711 incident replay",
                "OPS-1",
                totalItems,
                null,
                BulkJob.ScopeKind.ERROR_CLASS,
                "order v3 · error class");
    }

    private static BulkJobItem bulkItem(UUID jobId, int ordinal, String engineId, String instanceId) {
        BulkJobItem row = new BulkJobItem(jobId, ordinal, engineId, instanceId, null, BulkJobItem.State.pending);
        row.settle(
                BulkJobItem.State.ok,
                "job moved back to the executable queue",
                null,
                Instant.parse("2026-07-20T09:01:00Z"));
        return row;
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
