package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.inspector.security.ReadScopeGate;
import io.inspector.security.SecurityProperties;
import io.inspector.support.NoDbTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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

// #730 pinned the facts; #740 (decision 1 of #739) turned them into the wanted contract.
/**
 * Facts for issue #730 claim 1 — is {@code GET /api/engines} scope-filtered? Same rung and seam as
 * {@code IncidentScopeApiSpringTest}: the dev basic-auth ladder is global-scoped, so the
 * {@link ReadScopeGate} is overridden to emulate a VIEWER scoped to {@code probe-dev} only
 * (enforcement on), or to return {@code null} (enforcement off — the config default). The real
 * grant→readable-set derivation is {@code ReadScopeGateTest}'s job.
 *
 * <p>#740: with enforcement ON the engine list carries only engines the caller may read (like the
 * triage {@code engines} block), and the per-engine {@code /system} read refuses an out-of-scope engine
 * with the SAME 403 every other method-security refusal answers — also for a user who is ADMIN
 * elsewhere. With enforcement OFF both answer exactly as before.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
@AutoConfigureTestRestTemplate
class EngineListScopeFactsTest {

    /** Every field an {@code EngineDto} carries on the wire today — per engine, scoped or not. */
    private static final Set<String> ENGINE_DTO_FIELDS = new TreeSet<>(List.of(
            "id",
            "name",
            "environment",
            "accentColor",
            "mode",
            "lifecycle",
            "tenantId",
            "reachable",
            "engineVersion",
            "lastHealthCheck",
            "capabilities",
            "jobLanes",
            "oldestExecutableJobAge",
            "overdueTimers",
            "healthError",
            "oldestHistoryJobAge",
            "adviceLevel",
            "advice",
            "startedCurrent",
            "startedReference",
            "throughput"));

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    SecurityProperties security;

    @MockitoBean
    ReadScopeGate gate;

    private TestRestTemplate viewer() {
        return rest.withBasicAuth("viewer", "dev");
    }

    @Test
    void scopeReadsEnforcementIsOffByDefault() {
        // The test profile sets nothing, i.e. the application.yml default; only application-oidc.yml
        // turns it on (INSPECTOR_SCOPE_READS_ENFORCED:true).
        assertThat(security.scopeReadsEnforcedOrDefault()).isFalse();
    }

    @Test
    void enforcedScopedViewerSeesOnlyReadableEnginesInTheEngineList() throws Exception {
        scopedTo("probe-dev");

        JsonNode body = getJson("/api/engines");

        assertThat(ids(body)).containsExactly("probe-dev"); // probe-prod is OUT of scope: not listed
        JsonNode own = body.get(0);
        assertThat(fieldNames(own)).isEqualTo(ENGINE_DTO_FIELDS);
        assertThat(own.get("name").asText()).isEqualTo("Probe engine (dev twin)");
        assertThat(own.get("environment").asText()).isEqualTo("dev");
        assertThat(own.get("mode").asText()).isEqualTo("read-write");
        // never on the wire: base URL, auth type, any secret-ref name
        assertThat(body.toString()).doesNotContain("baseUrl", "base-url", "localhost:1", "auth", "Ref\"", "password");
    }

    @Test
    void unenforcedViewerSeesEveryEngineInTheEngineList() throws Exception {
        when(gate.enforced()).thenReturn(false);
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(null);

        JsonNode body = getJson("/api/engines");

        assertThat(ids(body)).containsExactly("probe-dev", "probe-prod");
        assertThat(fieldNames(body.get(1))).isEqualTo(ENGINE_DTO_FIELDS);
    }

    @Test
    void enforcedScopedViewerIsRefusedTheSystemPageOfAnOutOfScopeEngine() throws Exception {
        scopedTo("probe-dev");

        ResponseEntity<String> res = viewer().getForEntity("/api/engines/probe-prod/system", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refusal(res)).isEqualTo(refusal(scopedReadRefusal()));
        assertThat(res.getBody()).doesNotContain("version", "metricsStatus"); // no engine facts
    }

    @Test
    void enforcedScopedViewerSeesOnlyReadableEnginesInTheTriageEnginesBlock() throws Exception {
        when(gate.enforced()).thenReturn(true);
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(Set.of("probe-dev"));

        JsonNode triage = getJson("/api/triage");

        assertThat(ids(triage.get("engines"))).containsExactly("probe-dev"); // narrowed, unlike /api/engines
    }

    private JsonNode getJson(String path) throws Exception {
        ResponseEntity<String> res = viewer().getForEntity(path, String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(res.getBody());
    }

    private static List<String> ids(JsonNode engines) {
        List<String> out = new ArrayList<>();
        engines.forEach(e -> out.add(e.get("id").asText()));
        return out;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> out = new TreeSet<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    // ---- #740 additions -------------------------------------------------------------------------

    @Test
    void enforcedScopedViewerReadsTheVersionLineOfItsOwnEngine() throws Exception {
        scopedTo("probe-dev");

        JsonNode system = getJson("/api/engines/probe-dev/system");

        assertThat(system.get("engineId").asText()).isEqualTo("probe-dev");
        assertThat(system.get("metricsStatus").asText()).isEqualTo("version-only");
    }

    @Test
    void enforcedAdminOfOneEngineIsRefusedTheOtherEnginesSystemPage() throws Exception {
        // The side finding of #730: ROLE_ADMIN is not tied to an engine, so an admin scoped to
        // probe-dev read probe-prod's FULL system info (properties, table counts).
        scopedTo("probe-dev");

        ResponseEntity<String> res = admin().getForEntity("/api/engines/probe-prod/system", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refusal(res)).isEqualTo(refusal(scopedReadRefusal()));
    }

    @Test
    void enforcedFleetAdminReadsFullSystemInfoOfEveryEngine() throws Exception {
        scopedTo("probe-dev", "probe-prod");

        ResponseEntity<String> res = admin().getForEntity("/api/engines/probe-prod/system", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode system = mapper.readTree(res.getBody());
        assertThat(system.get("engineId").asText()).isEqualTo("probe-prod");
        assertThat(system.get("metricsStatus").asText()).isNotEqualTo("version-only"); // the full read
        assertThat(ids(admin().getForObject("/api/engines", JsonNode.class)))
                .containsExactly("probe-dev", "probe-prod");
    }

    @Test
    void unenforcedSystemPageIsUnchangedForViewerAndAdmin() throws Exception {
        when(gate.enforced()).thenReturn(false);
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(null);

        JsonNode asViewer = getJson("/api/engines/probe-prod/system");
        ResponseEntity<String> asAdmin = admin().getForEntity("/api/engines/probe-prod/system", String.class);

        assertThat(asViewer.get("metricsStatus").asText()).isEqualTo("version-only");
        assertThat(asAdmin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(asAdmin.getBody()).get("metricsStatus").asText())
                .isNotEqualTo("version-only");
    }

    private TestRestTemplate admin() {
        return rest.withBasicAuth("admin", "dev");
    }

    /** Emulates a session whose read scope is exactly {@code engineIds} (enforcement on). */
    private void scopedTo(String... engineIds) {
        Set<String> readable = Set.of(engineIds);
        when(gate.enforced()).thenReturn(true);
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(readable);
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
