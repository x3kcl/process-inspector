package io.inspector.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.EngineSeed;
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
 * Rung 4 for R-AUD-01, the fail-closed rule: the Postgres container is KILLED mid-test,
 * and a mutation whose target provably exists on the real engine must (a) come back as
 * the named fail-closed refusal and (b) never reach Flowable — the dead-letter job stays
 * exactly where it was. Own container + own Spring context: no other IT shares this
 * deliberately-broken database.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml up -d   (flowable-6 profile)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FailClosedAuditIT {

    private static final String ENGINE =
            "http://localhost:" + System.getenv().getOrDefault("PI_ENGINE_A_PORT", "8081") + "/flowable-rest/service";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        // A marker property keeps this context distinct from CorrectiveActionIT's, so the
        // dying database below can never leak into another class's cached context.
        registry.add("inspector.it.fail-closed", () -> "true");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    private RestClient engine;

    @BeforeAll
    void seedOrganically() {
        engine = EngineSeed.requireReachable(ENGINE, "");
        EngineSeed.deployIfMissing(engine, "demoFailingPayment", EngineSeed.FAILING_PAYMENT_BPMN);
    }

    @Test
    void mutationIsRefusedAndNeverReachesFlowableWhenTheAuditStoreIsDown() throws Exception {
        String instanceId = EngineSeed.startFailing(engine, "demoFailingPayment", "it-failclosed-" + UUID.randomUUID());
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> EngineSeed.deadLetterCountFor(engine, instanceId) == 1);
        String jobId = EngineSeed.deadLetterJobIdFor(engine, instanceId);

        // the database dies mid-incident
        POSTGRES.stop();

        ResponseEntity<String> response = rest.withBasicAuth("responder", "dev")
                .postForEntity(
                        "/api/instances/engine-a/" + instanceId + "/actions/retry-job",
                        Map.of("jobId", jobId),
                        String.class);

        // (a) the named fail-closed refusal — a 5xx that says NOTHING happened and names the store
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        JsonNode problem = mapper.readTree(response.getBody());
        assertThat(problem.path("code").asText()).isEqualTo("audit-unavailable");
        assertThat(problem.path("detail").asText()).contains("Postgres").contains("NOT sent");

        // (b) the mutation never reached Flowable: the job is STILL dead-lettered, and stays so
        await().during(3, TimeUnit.SECONDS)
                .atMost(10, TimeUnit.SECONDS)
                .until(() -> EngineSeed.deadLetterCountFor(engine, instanceId) == 1);
    }
}
