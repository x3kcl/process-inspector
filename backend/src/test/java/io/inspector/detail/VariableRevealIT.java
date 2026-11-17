package io.inspector.detail;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.EngineSeed;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Rung 4 for #743: the audited reveal against the REAL 6.8 engine and a Testcontainers Postgres —
 * an operator reveals a real secret-named variable, the audit row is read back through the BFF
 * (name present, value absent), and a viewer is refused with the refusal on record.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml up -d   (flowable-6 profile)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@AutoConfigureTestRestTemplate
class VariableRevealIT {

    private static final String ENGINE =
            "http://localhost:" + System.getenv().getOrDefault("PI_ENGINE_A_PORT", "8081") + "/flowable-rest/service";

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
    private String instanceId;
    private final String secret = "tok-it-743-" + UUID.randomUUID();

    @BeforeAll
    void seed() {
        engine = EngineSeed.requireReachable(ENGINE, "");
        EngineSeed.deployIfMissing(engine, "demoUserTask", EngineSeed.USER_TASK_BPMN);
        instanceId = EngineSeed.startInstance(
                engine,
                "demoUserTask",
                "it-reveal-" + UUID.randomUUID(),
                List.of(
                        Map.of("name", "apiToken", "type", "string", "value", secret),
                        Map.of("name", "amount", "type", "integer", "value", 100)));
    }

    @AfterAll
    void cleanup() {
        EngineSeed.deleteInstanceQuietly(engine, instanceId);
    }

    private ResponseEntity<String> reveal(String user, String name) {
        return rest.withBasicAuth(user, "dev")
                .postForEntity(
                        "/api/instances/engine-a/" + instanceId + "/variables/" + name + "/reveal",
                        Map.of("reason", "IT-743 audited reveal"),
                        String.class);
    }

    @Test
    void anOperatorRevealsARealSecretTheRowIsReadBackAndAViewerIsRefused() throws Exception {
        ResponseEntity<String> read = rest.withBasicAuth("operator", "dev")
                .getForEntity("/api/instances/engine-a/" + instanceId + "/variables/apiToken", String.class);
        assertThat(read.getBody()).doesNotContain(secret);

        ResponseEntity<String> revealed = reveal("operator", "apiToken");
        ResponseEntity<String> refused = reveal("viewer", "apiToken");
        ResponseEntity<String> notSecret = reveal("operator", "amount");

        assertThat(revealed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revealed.getHeaders().getCacheControl()).contains("no-store");
        assertThat(mapper.readTree(revealed.getBody()).path("value").asText()).isEqualTo(secret);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody()).doesNotContain(secret);
        assertThat(notSecret.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> trail = rest.withBasicAuth("operator", "dev")
                .getForEntity("/api/instances/engine-a/" + instanceId + "/audit", String.class);
        assertThat(trail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(trail.getBody()).doesNotContain(secret);
        JsonNode rows = mapper.readTree(trail.getBody());
        List<String> outcomes = new java.util.ArrayList<>();
        for (JsonNode row : rows) {
            assertThat(row.path("action").asText()).isEqualTo("reveal-variable");
            outcomes.add(row.path("actor").asText() + ":" + row.path("outcome").asText() + ":"
                    + row.path("httpStatus").asText());
            assertThat(row.path("reason").asText()).isEqualTo("IT-743 audited reveal");
        }
        assertThat(outcomes).containsExactlyInAnyOrder("operator:ok:200", "viewer:failed:403", "operator:failed:400");
        assertThat(trail.getBody()).contains("apiToken");
    }
}
