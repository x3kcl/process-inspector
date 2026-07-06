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
 * Rung 4, cross-version: the triage aggregation on Flowable 7.1 — the Spring Boot 3 /
 * Jakarta baseline where the error payload SHAPE drifted (SPEC §10): 7.x appends
 * {@code with Execution[ id '…' ] - definition '…' - …} runtime IDs to every exception
 * message (6.x does not; proven live 2026-07-06). The normalizer must parse it, strip
 * the tail, and still resolve the root-cause class — a silent parser failure would
 * degrade every 7.x failure into one unparseable group.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml --profile flowable-7 up -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_7_PASSWORD=test")
@ActiveProfiles("it7-triage")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Triage7IT {

    private static final String ENGINE = "http://localhost:8083/flowable-rest/service";

    @Autowired
    TestRestTemplate rest;

    private RestClient engine;
    private int paymentVersion;

    @BeforeAll
    void seedOrganically() {
        engine = EngineSeed.requireReachable(ENGINE, "--profile flowable-7");
        EngineSeed.deployIfMissing(engine, "demoFailingPayment", EngineSeed.FAILING_PAYMENT_BPMN);
        EngineSeed.deployIfMissing(engine, "demoFailingRetry", EngineSeed.FAILING_RETRY_BPMN);
        paymentVersion = EngineSeed.latestVersion(engine, "demoFailingPayment");
        String failing = EngineSeed.startFailingPayment(engine);
        String retrying = EngineSeed.startFailing(engine, "demoFailingRetry", null);
        await().atMost(60, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(EngineSeed.deadLetterCountFor(engine, failing)).isGreaterThanOrEqualTo(1);
            assertThat(EngineSeed.failingTimerCountFor(engine, retrying)).isGreaterThanOrEqualTo(1);
        });
    }

    @Test
    void parsesTheJakartaErrorShapeIntoTheSameRootCauseSignature() {
        JsonNode body = rest.getForObject("/api/triage?refresh=true", JsonNode.class);
        assertThat(body.get("perEngine").get("engine-7").get("ok").asBoolean()).isTrue();

        JsonNode arithmetic = null;
        for (JsonNode group : body.get("errorGroups")) {
            // ZERO unparseable groups — the exact quiet failure SPEC §10 forbids.
            assertThat(group.get("normalizedMessage").asText()).isNotBlank();
            assertThat(group.get("signatureHash").asText()).hasSize(64);
            if ("java.lang.ArithmeticException"
                    .equals(group.get("exceptionClass").asText(null))) {
                arithmetic = group;
            }
        }
        assertThat(arithmetic)
                .as("arithmetic root cause resolved from the 7.x stacktrace")
                .isNotNull();

        // The RAW 7.x message carries the execution-context tail; the normalized one
        // must not — that tail is per-instance runtime IDs, the anti-grouping poison.
        assertThat(arithmetic.get("sampleRawMessage").asText()).contains("with Execution[");
        assertThat(arithmetic.get("normalizedMessage").asText())
                .isEqualTo("/ by zero")
                .doesNotContain("Execution[");

        // per-version split + the RETRYING tier (timer lane) both flow on 7.x
        assertThat(arithmetic
                        .get("countsByEngine")
                        .get("engine-7")
                        .get("demoFailingPayment:v" + paymentVersion)
                        .asLong())
                .isGreaterThanOrEqualTo(1);
        assertThat(arithmetic.get("retryingCount").asLong()).isGreaterThanOrEqualTo(1);

        // status-count legs bind on the Jakarta wire shapes too
        JsonNode counts = body.get("statusCountsByEngine").get("engine-7");
        assertThat(counts.get("ACTIVE").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(counts.has("SUSPENDED")).isTrue();
        assertThat(counts.has("COMPLETED")).isTrue();
    }
}
