package io.inspector.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.EngineSeed;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * Rung 4 (engine-harness): the definition-versions on-ramp against REAL flowable-rest 6.8 —
 * per-version RUNNING instance counts, count-only. Local-only (not in ci.yml itClass).
 * Requires: docker compose -f docker/docker-compose.dev.yml up -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefinitionVersionsIT {

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
    private int fromVersion;
    private int toVersion;
    private String fromDefId;

    @BeforeAll
    void seedTwoVersions() {
        engine = EngineSeed.requireReachable(ENGINE, "");
        fromVersion = EngineSeed.deployNewVersion(engine, KEY, V1_BPMN);
        toVersion = EngineSeed.deployNewVersion(engine, KEY, V2_BPMN);
        fromDefId = definitionIdForVersion(fromVersion);
    }

    @SuppressWarnings("unchecked")
    private String definitionIdForVersion(int version) {
        Map<String, Object> page = engine.get()
                .uri("/repository/process-definitions?key=" + KEY + "&version=" + version)
                .retrieve()
                .body(Map.class);
        return String.valueOf(
                ((List<Map<String, Object>>) page.get("data")).get(0).get("id"));
    }

    private void startOn(String definitionId) {
        engine.post()
                .uri("/runtime/process-instances")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("processDefinitionId", definitionId))
                .retrieve()
                .toBodilessEntity();
    }

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    @Test
    void versionsListReportsPerVersionRunningCounts() throws Exception {
        // count-before, start two on the from-version, count-after → the delta must be exactly two.
        long before = versionCount(fromVersion);
        startOn(fromDefId);
        startOn(fromDefId);

        ResponseEntity<String> response =
                as("viewer").getForEntity("/api/definitions/engine-a/" + KEY + "/versions", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(response.getBody());

        assertThat(body.path("latestVersion").asInt()).isEqualTo(toVersion);

        JsonNode from = versionEntry(body, fromVersion);
        assertThat(from.path("runningInstanceCount").asLong()).isEqualTo(before + 2);
        assertThat(from.path("latest").asBoolean()).isFalse();

        JsonNode to = versionEntry(body, toVersion);
        assertThat(to.path("latest").asBoolean()).isTrue();

        // versions are newest-first
        JsonNode versions = body.path("versions");
        assertThat(versions.get(0).path("version").asInt())
                .isGreaterThan(versions.get(1).path("version").asInt());
    }

    @Test
    void unknownKeyIs404() {
        ResponseEntity<String> response =
                as("viewer").getForEntity("/api/definitions/engine-a/noSuchKey/versions", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private long versionCount(int version) throws Exception {
        JsonNode body = mapper.readTree(as("viewer")
                .getForEntity("/api/definitions/engine-a/" + KEY + "/versions", String.class)
                .getBody());
        return versionEntry(body, version).path("runningInstanceCount").asLong();
    }

    private static JsonNode versionEntry(JsonNode body, int version) {
        for (JsonNode v : body.path("versions")) {
            if (v.path("version").asInt() == version) {
                return v;
            }
        }
        throw new AssertionError("no entry for version " + version + " in " + body.path("versions"));
    }
}
