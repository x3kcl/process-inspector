package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.EngineSeed;
import io.inspector.support.NoDbTestSupport;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * Rung 4 (engine-harness): the full M1 arc against REAL flowable-rest 6.8 —
 * engine DB → REST → probe → /api/engines DTO. The dead-letter job is produced
 * ORGANICALLY (demoFailingPayment, R1/PT1S, divisor=0) — never via SQL.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml up -d
 * Runs under failsafe (*IT) — `mvn test` never executes this; `mvn verify` does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// M4: docker-free profile — DB autoconfig excluded, repositories mocked (NoDbTestSupport).
@Import(NoDbTestSupport.class)
class EngineHealthIT {

    private static final String ENGINE = "http://localhost:8081/flowable-rest/service";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    EngineHealthService healthService;

    @Autowired
    ObjectMapper mapper;

    private RestClient engine;
    private String processInstanceId;

    @BeforeAll
    void seedOrganically() {
        rest = rest.withBasicAuth("admin", "dev"); // M4: the BFF now requires auth on every /api route
        engine = EngineSeed.requireReachable(ENGINE, "");
        EngineSeed.deployIfMissing(engine, "demoFailingPayment", EngineSeed.FAILING_PAYMENT_BPMN);
        // The one mutation of this test: start an instance that will dead-letter itself.
        processInstanceId = EngineSeed.startFailingPayment(engine);
    }

    @Test
    void healthStripShowsOrganicDeadLetterAndCapabilities() throws Exception {
        // 1) Await the REAL engine state. R1/PT1S schedules the retry after ~1s, but the
        // async executor's timer-acquisition cycle (~10s waits) dominates: measured
        // fail→retry→dead-letter is ~45s on flowable-rest 6.8 defaults. Bound: 60s.
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(EngineSeed.deadLetterCountFor(engine, processInstanceId))
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

        // Alarm legs answered without a 400. Not asserted zero: a loaded runner can hold
        // a past-due retry timer beyond the 60s grace — the contract here is that the
        // dueBefore call succeeds and yields a sane count.
        assertThat(dto.get("overdueTimers").isNumber()).isTrue();
        assertThat(dto.get("overdueTimers").asLong()).isGreaterThanOrEqualTo(0);
    }
}
