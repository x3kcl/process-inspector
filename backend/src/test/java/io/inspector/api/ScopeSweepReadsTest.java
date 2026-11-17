package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.inspector.client.IdentityLinkRecord;
import io.inspector.client.JobPage;
import io.inspector.client.JobRecord;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.TaskPage;
import io.inspector.client.TaskRecord;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.security.ReadScopeGate;
import io.inspector.support.NoDbTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * #747 (child E5 of #739, R-SAFE-17): the reads the #746 reviewer found fanning out over every
 * engine with no read scope — the jobs explorer, the task browse and the task identity links. Same
 * rung and seam as {@code EngineListScopeFactsTest}: the dev ladder is global-scoped, so the
 * {@link ReadScopeGate} is overridden to emulate a VIEWER scoped to {@code probe-dev} only
 * (enforcement on), and the engine client answers one row per engine so a leak is visible as data.
 *
 * <p>Wanted: under enforcement a scoped caller never reaches {@code probe-prod} — the fan-outs
 * narrow silently (no engine was named), the path-named read answers the same method-security 403
 * every other engine-scoped read gives. Off: every engine, as before.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
@AutoConfigureTestRestTemplate
class ScopeSweepReadsTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    ReadScopeGate gate;

    @MockitoBean
    ProcessApiClient flowable;

    @BeforeEach
    void oneRowPerEngine() {
        JobRecord job = mock(JobRecord.class);
        when(flowable.listJobs(any(), any(), any(), anyMap(), anyInt(), anyInt()))
                .thenReturn(new JobPage(List.of(job), 1, 0, 1));
        TaskRecord task = mock(TaskRecord.class);
        when(flowable.listRuntimeTasks(any(EngineConfig.class), any(), anyMap(), anyInt()))
                .thenReturn(new TaskPage(List.of(task), 1, 0, 1));
        when(flowable.listIdentityLinks(any(), any(), anyString()))
                .thenReturn(
                        List.of(new IdentityLinkRecord("kermit", "kermit", null, null, "candidate", "t-747", null)));
    }

    // ---- 1. GET /api/jobs ------------------------------------------------------------------------

    @Test
    void enforcedScopedViewerSeesOnlyItsOwnEnginesJobs() throws Exception {
        scopedTo("probe-dev");

        JsonNode page = getJson("/api/jobs?lane=deadletter");

        assertThat(engineIds(page.get("rows"))).containsExactly("probe-dev");
        assertThat(page.get("cappedEngines")).isEmpty();
        verify(flowable, never())
                .listJobs(argThat(e -> "probe-prod".equals(e.id())), any(), any(), anyMap(), anyInt(), anyInt());
    }

    @Test
    void unenforcedJobsExplorerStillReadsEveryEngine() throws Exception {
        unenforced();

        JsonNode page = getJson("/api/jobs?lane=deadletter");

        assertThat(engineIds(page.get("rows"))).containsExactly("probe-dev", "probe-prod");
    }

    // ---- 2. GET /api/tasks/browse ----------------------------------------------------------------

    @Test
    void enforcedScopedViewerBrowsesOnlyItsOwnEnginesTasks() throws Exception {
        scopedTo("probe-dev");

        JsonNode page = getJson("/api/tasks/browse");

        assertThat(engineIds(page.get("rows"))).containsExactly("probe-dev");
        assertThat(page.get("cappedEngines")).isEmpty();
        verify(flowable, never())
                .listRuntimeTasks(argThat((EngineConfig e) -> "probe-prod".equals(e.id())), any(), anyMap(), anyInt());
    }

    @Test
    void unenforcedTaskBrowseStillReadsEveryEngine() throws Exception {
        unenforced();

        JsonNode page = getJson("/api/tasks/browse");

        assertThat(engineIds(page.get("rows"))).containsExactly("probe-dev", "probe-prod");
    }

    // ---- 3. GET /api/tasks/{engineId}/{taskId}/identitylinks -------------------------------------

    @Test
    void enforcedScopedViewerIsRefusedAnOutOfScopeEnginesTaskIdentityLinks() throws Exception {
        scopedTo("probe-dev");

        ResponseEntity<String> res = viewer().getForEntity("/api/tasks/probe-prod/t-747/identitylinks", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refusal(res)).isEqualTo(refusal(scopedReadRefusal()));
        verify(flowable, never()).listIdentityLinks(any(), any(), anyString());
    }

    @Test
    void enforcedScopedViewerReadsItsOwnEnginesTaskIdentityLinks() throws Exception {
        scopedTo("probe-dev");

        JsonNode links = getJson("/api/tasks/probe-dev/t-747/identitylinks");

        assertThat(links).hasSize(1);
        assertThat(links.get(0).get("identityId").asText()).isEqualTo("kermit");
    }

    @Test
    void unenforcedTaskIdentityLinksAreUnchanged() throws Exception {
        unenforced();

        JsonNode links = getJson("/api/tasks/probe-prod/t-747/identitylinks");

        assertThat(links.get(0).get("family").asText()).isEqualTo("users");
    }

    // ---- helpers -------------------------------------------------------------------------------

    private TestRestTemplate viewer() {
        return rest.withBasicAuth("viewer", "dev");
    }

    private JsonNode getJson(String path) throws Exception {
        ResponseEntity<String> res = viewer().getForEntity(path, String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(res.getBody());
    }

    private static List<String> engineIds(JsonNode rows) {
        List<String> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.get("engineId").asText()));
        return out;
    }

    private void unenforced() {
        when(gate.enforced()).thenReturn(false);
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(null);
        when(gate.canRead(any(Authentication.class), anyString())).thenReturn(true);
    }

    /** Emulates a session whose read scope is exactly {@code engineIds} (enforcement on). */
    private void scopedTo(String... engineIds) {
        when(gate.enforced()).thenReturn(true);
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(Set.of(engineIds));
        when(gate.canRead(any(Authentication.class), anyString())).thenReturn(false);
        for (String id : engineIds) {
            when(gate.canRead(any(Authentication.class), eq(id))).thenReturn(true);
        }
    }

    /** A method-security 403 every scoped read answers (here: a VIEWER at an ADMIN-floor read door). */
    private ResponseEntity<String> scopedReadRefusal() {
        ResponseEntity<String> ref = viewer().getForEntity("/api/admin/audit/integrity", String.class);
        assertThat(ref.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        return ref;
    }

    /** The refusal minus its per-request fields (request id, request path). */
    private JsonNode refusal(ResponseEntity<String> res) throws Exception {
        ObjectNode body = (ObjectNode) mapper.readTree(res.getBody());
        body.remove(List.of("requestId", "instance", "path", "timestamp"));
        return body;
    }
}
