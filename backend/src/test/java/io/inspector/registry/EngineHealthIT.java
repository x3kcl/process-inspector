package io.inspector.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Rung 4 (engine-harness): the full M1 arc against REAL flowable-rest 6.8 —
 * engine DB → REST → probe → /api/engines DTO. The dead-letter job is produced
 * ORGANICALLY (demoFailingPayment, R1/PT1S, divisor=0) — never via SQL.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml up -d
 * Runs under failsafe (*IT) — `mvn test` never executes this; `mvn verify` does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EngineHealthIT {

    private static final String ENGINE = "http://localhost:8081/flowable-rest/service";
    private static final Path SEED_BPMN =
            Path.of("..", "docker", "processes", "demo-failing-payment.bpmn20.xml");

    private static final RestClient engine = RestClient.builder()
            .baseUrl(ENGINE)
            .defaultHeaders(h -> h.setBasicAuth("rest-admin", "test"))
            .build();

    @Autowired TestRestTemplate rest;
    @Autowired EngineHealthService healthService;
    @Autowired ObjectMapper mapper;

    private String processInstanceId;

    @BeforeAll
    void seedOrganically() {
        try {
            engine.get().uri("/management/engine").retrieve().toBodilessEntity();
        } catch (Exception e) {
            fail("engine-a is not reachable on :8081 — start the harness first: "
                    + "docker compose -f docker/docker-compose.dev.yml up -d  (" + e.getMessage() + ")");
        }
        assertThat(SEED_BPMN).exists();

        // Idempotent REST deploy; deployment 2xx != parsed, so assert the definition list.
        if (definitionCount() == 0) {
            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            parts.add("file", new FileSystemResource(SEED_BPMN));
            engine.post().uri("/repository/deployments")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .toBodilessEntity();
        }
        assertThat(definitionCount())
                .as("demoFailingPayment must be deployed AND parsed")
                .isGreaterThanOrEqualTo(1);

        // The one mutation of this test: start an instance that will dead-letter itself.
        Map<String, Object> started = engine.post().uri("/runtime/process-instances")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "processDefinitionKey", "demoFailingPayment",
                        "variables", List.of(
                                Map.of("name", "amount", "type", "integer", "value", 100),
                                Map.of("name", "divisor", "type", "integer", "value", 0))))
                .retrieve()
                .body(Map.class);
        processInstanceId = String.valueOf(started.get("id"));
    }

    @Test
    void healthStripShowsOrganicDeadLetterAndCapabilities() throws Exception {
        // 1) Await the REAL engine state. R1/PT1S schedules the retry after ~1s, but the
        // async executor's timer-acquisition cycle (~10s waits) dominates: measured
        // fail→retry→dead-letter is ~45s on flowable-rest 6.8 defaults. Bound: 60s.
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(deadLetterCountFor(processInstanceId))
                        .isGreaterThanOrEqualTo(1));

        // 2) One deterministic probe cycle — no schedule-waiting.
        healthService.probeAll();

        // 3) Assert through the BFF's own API: engine DB → REST → probe → DTO.
        ResponseEntity<String> response = rest.getForEntity("/api/engines", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode engines = mapper.readTree(response.getBody());
        assertThat(engines).hasSize(1);
        JsonNode dto = engines.get(0);

        assertThat(dto.get("id").asText()).isEqualTo("engine-a");
        assertThat(dto.get("reachable").asBoolean()).isTrue();
        assertThat(dto.get("engineVersion").asText()).startsWith("6.8");
        assertThat(dto.get("healthError").isNull()).isTrue();

        JsonNode caps = dto.get("capabilities");
        assertThat(caps.get("changeState").asBoolean()).isTrue();
        assertThat(caps.get("migration").asBoolean()).isTrue();
        assertThat(caps.get("externalWorkerJobs").asBoolean()).isTrue();
        assertThat(caps.get("scopeType").asBoolean()).isTrue();
        assertThat(caps.get("activityHistory").asBoolean()).isTrue();

        JsonNode lanes = dto.get("jobLanes");
        assertThat(lanes.get("deadletter").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(lanes.get("executable").asLong()).isGreaterThanOrEqualTo(0);

        // Alarm legs answered without a 400: overdueTimers is a number (retry timers due
        // in the FUTURE never count — the grace period is 60s in the past).
        assertThat(dto.get("overdueTimers").isNumber()).isTrue();
        assertThat(dto.get("overdueTimers").asLong()).isZero();
    }

    private long definitionCount() {
        Map<String, Object> page = engine.get()
                .uri("/repository/process-definitions?key=demoFailingPayment&latest=true")
                .retrieve()
                .body(Map.class);
        return ((Number) page.get("total")).longValue();
    }

    private long deadLetterCountFor(String pid) {
        Map<String, Object> page = engine.get()
                .uri("/management/deadletter-jobs?processInstanceId=" + pid)
                .retrieve()
                .body(Map.class);
        return ((Number) page.get("total")).longValue();
    }
}
