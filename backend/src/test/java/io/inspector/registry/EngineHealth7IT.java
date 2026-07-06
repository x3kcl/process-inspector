package io.inspector.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.EngineSeed;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Rung 4 against the flowable-7 compose profile: proves the whole M1 surface — seed
 * deploy, organic dead-letter, capability derivation, lane counts, alarm legs — on the
 * Flowable 7.x / Jakarta wire shapes, not just 6.8.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml --profile flowable-7 up -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ENGINE_7_PASSWORD=test")
@ActiveProfiles("it7")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EngineHealth7IT {

    private static final String ENGINE = "http://localhost:8083/flowable-rest/service";

    @Autowired TestRestTemplate rest;
    @Autowired EngineHealthService healthService;
    @Autowired ObjectMapper mapper;

    private RestClient engine;
    private String processInstanceId;

    @BeforeAll
    void seedOrganically() {
        engine = EngineSeed.requireReachable(ENGINE, "--profile flowable-7");
        EngineSeed.deployIfMissing(engine, "demoFailingPayment", EngineSeed.FAILING_PAYMENT_BPMN);
        processInstanceId = EngineSeed.startFailingPayment(engine);
    }

    @Test
    void probeWorksAgainstFlowable7() throws Exception {
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(EngineSeed.deadLetterCountFor(engine, processInstanceId))
                        .isGreaterThanOrEqualTo(1));

        healthService.probeAll();

        JsonNode dto = mapper.readTree(rest.getForObject("/api/engines", String.class)).get(0);
        assertThat(dto.get("id").asText()).isEqualTo("engine-7");
        assertThat(dto.get("reachable").asBoolean()).isTrue();
        assertThat(dto.get("engineVersion").asText()).startsWith("7.");
        assertThat(dto.get("healthError").isNull()).isTrue();

        // 7.x passes every version cliff (ARCH §2.5).
        JsonNode caps = dto.get("capabilities");
        for (String cap : new String[] {"changeState", "migration", "externalWorkerJobs", "scopeType", "activityHistory"}) {
            assertThat(caps.get(cap).asBoolean()).as(cap).isTrue();
        }

        assertThat(dto.get("jobLanes").get("deadletter").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(dto.get("overdueTimers").asLong()).isZero();
    }
}
