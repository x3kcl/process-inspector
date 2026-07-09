package io.inspector.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.EngineSeed;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * Rung 4 (engine-harness): the S1 instance-migration PREVIEW against REAL flowable-rest 6.8.
 * Deploys two versions of {@code demoMigration} over REST (v2 renames reviewTask→approveTask),
 * starts an instance on the reviewTask version, and proves the BFF static auto-map pre-check
 * flags the renamed activity, honors an operator mapping, and refuses the scope boundaries
 * (suspended / cross-key / same-version). Preview is read-only — no mutation, no audit row.
 *
 * <p>Local-only (not in ci.yml itClass, like FlowSurgeryIT). Requires:
 * docker compose -f docker/docker-compose.dev.yml up -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationIT {

    private static final String ENGINE = "http://localhost:8081/flowable-rest/service";
    private static final String KEY = "demoMigration";
    private static final Path V1_BPMN = Path.of("..", "docker", "processes", "demo-migration-v1.bpmn20.xml");
    private static final Path V2_BPMN = Path.of("..", "docker", "processes", "demo-migration-v2.bpmn20.xml");

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
    private String fromDefId; // reviewTask version
    private String toDefId; // approveTask version
    private String otherKeyDefId; // a different key, for the cross-key guard

    @BeforeAll
    void seedTwoVersionsAndAwaitCapability() {
        engine = EngineSeed.requireReachable(ENGINE, "");
        // A fresh consecutive pair every run: vFrom has reviewTask, vTo renames it to approveTask.
        int vFrom = EngineSeed.deployNewVersion(engine, KEY, V1_BPMN);
        int vTo = EngineSeed.deployNewVersion(engine, KEY, V2_BPMN);
        fromDefId = definitionIdForVersion(KEY, vFrom);
        toDefId = definitionIdForVersion(KEY, vTo);

        EngineSeed.deployIfMissing(engine, "demoUserTask", EngineSeed.USER_TASK_BPMN);
        otherKeyDefId = definitionIdForVersion("demoUserTask", EngineSeed.latestVersion(engine, "demoUserTask"));

        // The migration capability gate is fail-closed until the first health probe answers.
        await().atMost(60, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).until(() -> {
            JsonNode engines = mapper.readTree(
                    as("viewer").getForEntity("/api/engines", String.class).getBody());
            for (JsonNode e : engines) {
                if ("engine-a".equals(e.path("id").asText())) {
                    return e.path("capabilities").path("migration").asBoolean(false);
                }
            }
            return false;
        });
    }

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    private ResponseEntity<String> preview(String instanceId, Map<String, Object> body) {
        return as("admin")
                .postForEntity("/api/instances/engine-a/" + instanceId + "/migrate/preview", body, String.class);
    }

    @SuppressWarnings("unchecked")
    private String definitionIdForVersion(String key, int version) {
        Map<String, Object> page = engine.get()
                .uri("/repository/process-definitions?key=" + key + "&version=" + version)
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) page.get("data");
        return String.valueOf(data.get(0).get("id"));
    }

    private String startOn(String definitionId) {
        Map<String, Object> started = engine.post()
                .uri("/runtime/process-instances")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("processDefinitionId", definitionId))
                .retrieve()
                .body(Map.class);
        return String.valueOf(started.get("id"));
    }

    @SuppressWarnings("unchecked")
    private List<String> activeActivities(String instanceId) {
        Map<String, Object> page = engine.get()
                .uri("/history/historic-activity-instances?processInstanceId=" + instanceId + "&finished=false")
                .retrieve()
                .body(Map.class);
        return ((List<Map<String, Object>>) page.get("data"))
                .stream().map(row -> String.valueOf(row.get("activityId"))).toList();
    }

    /* ---------------------------------------------------------------------------
     * The marquee arc: a bad deploy renamed reviewTask→approveTask; the pre-check
     * flags exactly that activity, honestly labelled, and mutates nothing.
     * --------------------------------------------------------------------------- */
    @Test
    void previewFlagsTheRenamedActivityAndIsHonestlyLabelled() throws Exception {
        String instanceId = startOn(fromDefId);
        assertThat(activeActivities(instanceId)).containsExactly("reviewTask");

        ResponseEntity<String> response = preview(instanceId, Map.of("toDefinitionId", toDefId));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode preview = mapper.readTree(response.getBody());

        assertThat(preview.path("engineValidated").asBoolean(true)).isFalse();
        assertThat(preview.path("banner").asText()).contains("not a Flowable validation");
        assertThat(preview.path("executable").asBoolean(true)).isFalse();
        assertThat(preview.path("toProcessDefinitionId").asText()).isEqualTo(toDefId);
        assertThat(preview.path("callActivityChildCount").asInt(-1)).isZero();

        JsonNode review = activityEntry(preview, "reviewTask");
        assertThat(review.path("status").asText()).isEqualTo("FLAGGED_UNMAPPED");
        assertThat(review.path("toActivityId").isNull()).isTrue();

        // Read-only: nothing moved.
        assertThat(activeActivities(instanceId)).containsExactly("reviewTask");
    }

    @Test
    void operatorMappingMakesItExecutableAndBuildsTheExactDocument() throws Exception {
        String instanceId = startOn(fromDefId);

        Map<String, Object> body = Map.of(
                "toDefinitionId",
                toDefId,
                "mappings",
                List.of(Map.of("fromActivityId", "reviewTask", "toActivityId", "approveTask")));
        JsonNode preview = mapper.readTree(preview(instanceId, body).getBody());

        assertThat(preview.path("executable").asBoolean(false)).isTrue();
        assertThat(activityEntry(preview, "reviewTask").path("status").asText()).isEqualTo("MAPPED_BY_OVERRIDE");

        JsonNode restBody = preview.path("restBody");
        assertThat(restBody.path("toProcessDefinitionId").asText()).isEqualTo(toDefId);
        JsonNode mapping = restBody.path("activityMappings").get(0);
        assertThat(mapping.path("fromActivityId").asText()).isEqualTo("reviewTask");
        assertThat(mapping.path("toActivityId").asText()).isEqualTo("approveTask");
    }

    @Test
    void defaultTargetResolvesToTheLatestVersion() throws Exception {
        // No toDefinitionId/toVersion → resolve the LATEST deployed version (latest=true, not a
        // plain size=1 which does not sort by version). vTo (approveTask) is the newest here.
        String instanceId = startOn(fromDefId);

        JsonNode preview = mapper.readTree(preview(instanceId, Map.of()).getBody());
        assertThat(preview.path("toProcessDefinitionId").asText()).isEqualTo(toDefId);
        // and it still flags the renamed active activity against that latest target
        assertThat(activityEntry(preview, "reviewTask").path("status").asText()).isEqualTo("FLAGGED_UNMAPPED");
    }

    @Test
    void suspendedInstanceIsRefused409() throws Exception {
        String instanceId = startOn(fromDefId);
        EngineSeed.suspend(engine, instanceId);

        ResponseEntity<String> response = preview(instanceId, Map.of("toDefinitionId", toDefId));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mapper.readTree(response.getBody()).path("code").asText()).isEqualTo("instance-suspended");
    }

    @Test
    void crossKeyTargetIsRefused422() throws Exception {
        String instanceId = startOn(fromDefId);

        ResponseEntity<String> response = preview(instanceId, Map.of("toDefinitionId", otherKeyDefId));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mapper.readTree(response.getBody()).path("code").asText()).isEqualTo("cross-key-migration");
    }

    @Test
    void sameVersionTargetIsRefused409() throws Exception {
        String instanceId = startOn(fromDefId);

        ResponseEntity<String> response = preview(instanceId, Map.of("toDefinitionId", fromDefId));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mapper.readTree(response.getBody()).path("code").asText()).isEqualTo("same-version-target");
    }

    private static JsonNode activityEntry(JsonNode preview, String fromActivityId) {
        for (JsonNode entry : preview.path("activities")) {
            if (fromActivityId.equals(entry.path("fromActivityId").asText())) {
                return entry;
            }
        }
        throw new AssertionError("no activity entry for '" + fromActivityId + "' in " + preview.path("activities"));
    }
}
