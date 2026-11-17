package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

// #730: pins CURRENT behaviour pending an owner decision — not an endorsement
/**
 * Facts for issue #730 claim 1 — is {@code GET /api/engines} scope-filtered? Same rung and seam as
 * {@code IncidentScopeApiSpringTest}: the dev basic-auth ladder is global-scoped, so the
 * {@link ReadScopeGate} is overridden to emulate a VIEWER scoped to {@code probe-dev} only
 * (enforcement on), or to return {@code null} (enforcement off — the config default). The real
 * grant→readable-set derivation is {@code ReadScopeGateTest}'s job.
 *
 * <p>Pinned today: the engine list ignores the gate either way and lists the out-of-scope engine with
 * the full {@code EngineDto} field set (no base URL, no auth block, no secret-ref name); the per-engine
 * {@code /system} read answers an out-of-scope engine too (version-only for a VIEWER); the triage
 * dashboard's {@code engines} block, by contrast, IS narrowed.
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
    void enforcedScopedViewerStillSeesEveryEngineInTheEngineList() throws Exception {
        when(gate.enforced()).thenReturn(true);
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(Set.of("probe-dev"));

        JsonNode body = getJson("/api/engines");

        assertThat(ids(body)).containsExactly("probe-dev", "probe-prod"); // probe-prod is OUT of scope
        JsonNode foreign = body.get(1);
        assertThat(fieldNames(foreign)).isEqualTo(ENGINE_DTO_FIELDS);
        assertThat(foreign.get("name").asText()).isEqualTo("Probe engine (prod twin)");
        assertThat(foreign.get("environment").asText()).isEqualTo("prod");
        assertThat(foreign.get("mode").asText()).isEqualTo("read-only");
        // never on the wire, in or out of scope: base URL, auth type, any secret-ref name
        assertThat(foreign.toString())
                .doesNotContain("baseUrl", "base-url", "localhost:1", "auth", "Ref\"", "password");
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
    void enforcedScopedViewerCanReadTheVersionLineOfAnOutOfScopeEngine() throws Exception {
        when(gate.enforced()).thenReturn(true);
        when(gate.readableEngineIds(any(Authentication.class))).thenReturn(Set.of("probe-dev"));

        JsonNode system = getJson("/api/engines/probe-prod/system");

        assertThat(system.get("engineId").asText()).isEqualTo("probe-prod");
        assertThat(system.get("metricsStatus").asText()).isEqualTo("version-only");
        assertThat(system.get("properties").isNull()).isTrue(); // properties/tables are ADMIN-only
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
}
