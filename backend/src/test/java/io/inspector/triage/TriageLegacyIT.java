package io.inspector.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import io.inspector.support.EngineSeed;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * Rung 4, cross-version: the triage aggregation on Flowable 6.3.1 — the pre-cliff
 * engine that silently DROPS unknown query fields (m2a wire-shape doctrine: silent
 * ignore is worse than a 400). Every triage leg was canary-probed live on 6.3.1
 * (runtime {@code suspended}, historic {@code finished}, job-lane {@code withException},
 * the stacktrace endpoint); this suite pins that the whole aggregation arc holds.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml --profile legacy up -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_LEGACY_PASSWORD=test")
@ActiveProfiles("it-legacy-triage")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TriageLegacyIT {

    private static final String ENGINE = "http://localhost:8084/flowable-rest/service";

    @Autowired
    TestRestTemplate rest;

    private RestClient engine;
    private int paymentVersion;

    @BeforeAll
    void seedOrganically() {
        engine = EngineSeed.requireReachable(ENGINE, "--profile legacy");
        EngineSeed.deployIfMissing(engine, "demoFailingPayment", EngineSeed.FAILING_PAYMENT_BPMN);
        EngineSeed.deployIfMissing(engine, "demoUserTask", EngineSeed.USER_TASK_BPMN);
        paymentVersion = EngineSeed.latestVersion(engine, "demoFailingPayment");
        String failing = EngineSeed.startFailingPayment(engine);
        String parked = EngineSeed.startInstance(engine, "demoUserTask", null, java.util.List.of());
        EngineSeed.suspend(engine, parked);
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(EngineSeed.deadLetterCountFor(engine, failing))
                        .isGreaterThanOrEqualTo(1));
    }

    @Test
    void allTriageLegsHoldOnThePreCliffEngine() {
        JsonNode body = rest.getForObject("/api/triage?refresh=true", JsonNode.class);
        assertThat(body.get("perEngine").get("engine-legacy").get("ok").asBoolean())
                .isTrue();

        // The suspended filter genuinely filters on 6.3.1 (not silently dropped): if the
        // engine ignored the flag, BOTH counts would be the full runtime total and their
        // sum would be twice it. Reconcile against the unfiltered total, fetched directly
        // from the engine right after the (refresh-bypassed) aggregation ran.
        JsonNode counts = body.get("statusCountsByEngine").get("engine-legacy");
        long runtimeTotal = ((Number) ((java.util.Map<String, Object>) engine.post()
                                .uri("/query/process-instances")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .body(java.util.Map.of("size", 1))
                                .retrieve()
                                .body(java.util.Map.class))
                        .get("total"))
                .longValue();
        assertThat(counts.get("SUSPENDED").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(counts.get("ACTIVE").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(counts.get("ACTIVE").asLong() + counts.get("SUSPENDED").asLong())
                .as("a silently-dropped 'suspended' flag would double-count the runtime set")
                .isEqualTo(runtimeTotal);

        // Root-cause grouping incl. stacktrace refinement + per-version split on 6.3.1.
        JsonNode arithmetic = null;
        for (JsonNode group : body.get("errorGroups")) {
            assertThat(group.get("normalizedMessage").asText()).isNotBlank();
            if ("java.lang.ArithmeticException"
                    .equals(group.get("exceptionClass").asText(null))) {
                arithmetic = group;
            }
        }
        assertThat(arithmetic).isNotNull();
        assertThat(arithmetic.get("normalizedMessage").asText()).isEqualTo("/ by zero");
        assertThat(arithmetic
                        .get("countsByEngine")
                        .get("engine-legacy")
                        .get("demoFailingPayment:v" + paymentVersion)
                        .asLong())
                .isGreaterThanOrEqualTo(1);
    }
}
