package io.inspector.surgery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.EngineSeed;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Rung 4 (engine-harness): the v1.1 flow-surgery rails against REAL flowable-rest 6.8 and
 * a REAL Postgres 16 — the change-state guardrails proven on a live token, the audit
 * golden master read back through the BFF, restart-as-new resurrecting a genuinely dead
 * instance. Fixtures seeded strictly over REST (demo-flow-surgery: user-task runway →
 * parallel fork/join → sequential MI subprocess).
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml up -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlowSurgeryIT {

    private static final String ENGINE =
            "http://localhost:" + System.getenv().getOrDefault("PI_ENGINE_A_PORT", "8081") + "/flowable-rest/service";
    private static final String REASON = "flow-surgery IT: INC-0 rehearsal move";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    private RestClient engine;
    private final String businessKey = "it-surgery-" + UUID.randomUUID();

    @BeforeAll
    void seedAndAwaitCapabilityProbe() {
        engine = EngineSeed.requireReachable(ENGINE, "");
        EngineSeed.deployIfMissing(engine, "demoFlowSurgery", EngineSeed.FLOW_SURGERY_BPMN);
        EngineSeed.deployIfMissing(engine, "demoOrder", EngineSeed.ORDER_BPMN);
        // The capability gate is fail-closed until the first health probe answers — wait
        // for the BFF to have PROVEN change-state support before exercising the verbs.
        await().atMost(60, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).until(() -> {
            JsonNode engines = mapper.readTree(
                    as("viewer").getForEntity("/api/engines", String.class).getBody());
            for (JsonNode e : engines) {
                if ("engine-a".equals(e.path("id").asText())) {
                    return e.path("capabilities").path("changeState").asBoolean(false);
                }
            }
            return false;
        });
    }

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    private String startSurgeryInstance(String keySuffix) {
        return EngineSeed.startInstance(engine, "demoFlowSurgery", businessKey + keySuffix, List.of());
    }

    /** The live unfinished activity ids of an instance — engine truth, not BFF opinion. */
    @SuppressWarnings("unchecked")
    private List<String> activeActivities(String instanceId) {
        Map<String, Object> page = engine.get()
                .uri("/history/historic-activity-instances?processInstanceId=" + instanceId + "&finished=false")
                .retrieve()
                .body(Map.class);
        return ((List<Map<String, Object>>) page.get("data"))
                .stream().map(row -> String.valueOf(row.get("activityId"))).toList();
    }

    /* ------------------------------------------------------------------------------
     * v1.1 done-when arc: preview shows exactly the REST call, execute moves the token
     * on the live engine, and the payload lands in the Postgres audit golden master.
     * ------------------------------------------------------------------------------ */
    @Test
    void changeStateMovesTheLiveTokenAndAuditsTheExactPayload() throws Exception {
        String instanceId = startSurgeryInstance("-move");
        assertThat(activeActivities(instanceId)).containsExactly("stepOne");

        Map<String, Object> body = Map.of(
                "reason", REASON, "sourceActivityIds", List.of("stepOne"), "targetActivityIds", List.of("stepTwo"));

        // Preview: the BFF simulation — exact body, no mutation, nothing audited yet.
        ResponseEntity<String> previewed = as("operator")
                .postForEntity("/api/instances/engine-a/" + instanceId + "/change-state/preview", body, String.class);
        assertThat(previewed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode preview = mapper.readTree(previewed.getBody());
        assertThat(preview.path("payload").path("cancelActivityIds").get(0).asText())
                .isEqualTo("stepOne");
        assertThat(preview.path("payload").path("startActivityIds").get(0).asText())
                .isEqualTo("stepTwo");
        assertThat(preview.path("simulationNote").asText()).contains("no dry-run");
        assertThat(activeActivities(instanceId)).containsExactly("stepOne"); // untouched

        // Execute: the ONE engine call — the token genuinely moves.
        ResponseEntity<String> executed = as("operator")
                .postForEntity("/api/instances/engine-a/" + instanceId + "/change-state/execute", body, String.class);
        assertThat(executed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode result = mapper.readTree(executed.getBody());
        assertThat(result.path("outcome").asText()).isEqualTo("ok");
        assertThat(result.path("deltaStatement").asText()).contains("stepTwo");
        assertThat(activeActivities(instanceId)).containsExactly("stepTwo");

        // The audit golden master holds the source, target AND the generated REST body.
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            JsonNode rows = mapper.readTree(as("operator")
                    .getForEntity("/api/instances/engine-a/" + instanceId + "/audit", String.class)
                    .getBody());
            assertThat(rows).isNotEmpty();
            JsonNode row = rows.get(0);
            assertThat(row.path("action").asText()).isEqualTo("change-state");
            assertThat(row.path("actor").asText()).isEqualTo("operator");
            assertThat(row.path("outcome").asText()).isEqualTo("ok");
            assertThat(row.path("payload").asText())
                    .contains("cancelActivityIds")
                    .contains("startActivityIds")
                    .contains("stepOne")
                    .contains("stepTwo");
        });
    }

    /* ------------------------------------------------------------------------------
     * The MI guardrail: a deployed multi-instance body, and the BFF refusing the jump
     * INTO it with 422 + the explicit reason — the engine never sees the call.
     * ------------------------------------------------------------------------------ */
    @Test
    void jumpIntoTheMultiInstanceBodyIsRefused422AndTheTokenNeverMoves() throws Exception {
        String instanceId = startSurgeryInstance("-mi");

        Map<String, Object> body = Map.of(
                "reason", REASON, "sourceActivityIds", List.of("stepOne"), "targetActivityIds", List.of("miTask"));
        for (String phase : List.of("preview", "execute")) {
            ResponseEntity<String> refused = as("operator")
                    .postForEntity(
                            "/api/instances/engine-a/" + instanceId + "/change-state/" + phase, body, String.class);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            JsonNode problem = mapper.readTree(refused.getBody());
            assertThat(problem.path("code").asText()).isEqualTo("multi-instance-body");
            assertThat(problem.path("detail").asText()).contains("miSub").contains("multi-instance");
        }
        assertThat(activeActivities(instanceId)).containsExactly("stepOne"); // nothing happened
    }

    @Test
    void parallelBranchTargetGetsTheSiblingTokenWarning() throws Exception {
        String instanceId = startSurgeryInstance("-warn");

        ResponseEntity<String> previewed = as("operator")
                .postForEntity(
                        "/api/instances/engine-a/" + instanceId + "/change-state/preview",
                        Map.of("sourceActivityIds", List.of("stepOne"), "targetActivityIds", List.of("branchA")),
                        String.class);

        assertThat(previewed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode warnings = mapper.readTree(previewed.getBody()).path("warnings");
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).path("code").asText()).isEqualTo("parallel-branch-target");
    }

    @Test
    void suspendedInstanceIsBlockedWithActivateFirstGuidance() throws Exception {
        String instanceId = startSurgeryInstance("-susp");
        EngineSeed.suspend(engine, instanceId);

        ResponseEntity<String> refused = as("operator")
                .postForEntity(
                        "/api/instances/engine-a/" + instanceId + "/change-state/execute",
                        Map.of(
                                "reason", REASON,
                                "sourceActivityIds", List.of("stepOne"),
                                "targetActivityIds", List.of("stepTwo")),
                        String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = mapper.readTree(refused.getBody());
        assertThat(problem.path("code").asText()).isEqualTo("instance-suspended");
        assertThat(problem.path("detail").asText()).contains("activate");
    }

    @Test
    void viewerCannotEvenPreview() {
        ResponseEntity<String> refused = as("viewer")
                .postForEntity(
                        "/api/instances/engine-a/ignored/change-state/preview",
                        Map.of("sourceActivityIds", List.of("a"), "targetActivityIds", List.of("b")),
                        String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /* ------------------------------------------------------------------------------
     * Restart-as-new: a COMPLETED instance resurrected onto a NEW id with its historic
     * variables carried over, both sides of the pin-vs-latest version fork proven.
     * ------------------------------------------------------------------------------ */
    @Test
    void restartAsNewCarriesHistoricVariablesOntoAFreshInstance() throws Exception {
        String key = businessKey + "-restart";
        String originalId = EngineSeed.startInstance(
                engine,
                "demoOrder",
                key,
                List.of(
                        Map.of("name", "amount", "type", "integer", "value", 42),
                        Map.of("name", "customer", "type", "string", "value", "acme")));
        // demoOrder is straight-through: already COMPLETED (runtime 404 = genuinely dead).
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> historicEndTime(originalId) != null);

        ResponseEntity<String> restarted = as("operator")
                .postForEntity(
                        "/api/instances/engine-a/" + originalId + "/restart",
                        Map.of("reason", REASON, "pinDefinitionVersion", false),
                        String.class);

        assertThat(restarted.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode result = mapper.readTree(restarted.getBody());
        String newId = result.path("newProcessInstanceId").asText();
        assertThat(newId).isNotBlank().isNotEqualTo(originalId);
        assertThat(result.path("outcome").asText()).isEqualTo("ok");
        assertThat(mapper.convertValue(result.path("carriedVariables"), List.class))
                .containsExactlyInAnyOrder("amount", "customer");

        // The NEW instance's history proves the copy: same variables, same typed values.
        Map<String, Object> variables = historicVariablesOf(newId);
        assertThat(variables).containsEntry("amount", 42).containsEntry("customer", "acme");

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            JsonNode rows = mapper.readTree(as("operator")
                    .getForEntity("/api/instances/engine-a/" + originalId + "/audit", String.class)
                    .getBody());
            assertThat(rows).isNotEmpty();
            assertThat(rows.get(0).path("action").asText()).isEqualTo("restart-as-new");
            assertThat(rows.get(0).path("outcome").asText()).isEqualTo("ok");
        });
    }

    @Test
    void theVersionForkPinsOrFollowsLatestExplicitly() throws Exception {
        String originalId = EngineSeed.startInstance(engine, "demoOrder", businessKey + "-fork", List.of());
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> historicEndTime(originalId) != null);
        String originalDefinitionId = historicDefinitionId(originalId);

        // A newer version exists AFTER the original ran.
        EngineSeed.deployNewVersion(engine, "demoOrder", EngineSeed.ORDER_BPMN);

        JsonNode pinned = mapper.readTree(as("operator")
                .postForEntity(
                        "/api/instances/engine-a/" + originalId + "/restart",
                        Map.of("reason", REASON, "pinDefinitionVersion", true),
                        String.class)
                .getBody());
        assertThat(pinned.path("processDefinitionId").asText()).isEqualTo(originalDefinitionId);

        JsonNode latest = mapper.readTree(as("operator")
                .postForEntity(
                        "/api/instances/engine-a/" + originalId + "/restart",
                        Map.of("reason", REASON, "pinDefinitionVersion", false),
                        String.class)
                .getBody());
        assertThat(latest.path("processDefinitionId").asText()).isNotBlank().isNotEqualTo(originalDefinitionId);
    }

    @Test
    void restartRefusesAStillRunningInstance() throws Exception {
        String instanceId = startSurgeryInstance("-alive");

        ResponseEntity<String> refused = as("operator")
                .postForEntity(
                        "/api/instances/engine-a/" + instanceId + "/restart",
                        Map.of("reason", REASON, "pinDefinitionVersion", false),
                        String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mapper.readTree(refused.getBody()).path("code").asText()).isEqualTo("instance-still-running");
    }

    /* ---------------- engine-truth helpers (REST only, never engine tables) ---------------- */

    @SuppressWarnings("unchecked")
    private Object historicEndTime(String instanceId) {
        Map<String, Object> row = engine.get()
                .uri("/history/historic-process-instances/{id}", instanceId)
                .retrieve()
                .body(Map.class);
        return row != null ? row.get("endTime") : null;
    }

    @SuppressWarnings("unchecked")
    private String historicDefinitionId(String instanceId) {
        Map<String, Object> row = engine.get()
                .uri("/history/historic-process-instances/{id}", instanceId)
                .retrieve()
                .body(Map.class);
        return String.valueOf(row.get("processDefinitionId"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> historicVariablesOf(String instanceId) {
        Map<String, Object> page = engine.get()
                .uri("/history/historic-variable-instances?processInstanceId=" + instanceId)
                .retrieve()
                .body(Map.class);
        Map<String, Object> flat = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) page.get("data")) {
            Map<String, Object> variable = (Map<String, Object>) row.get("variable");
            flat.put(String.valueOf(variable.get("name")), variable.get("value"));
        }
        return flat;
    }
}
