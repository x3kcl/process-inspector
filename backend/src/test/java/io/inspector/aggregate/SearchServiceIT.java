package io.inspector.aggregate;

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
import org.springframework.web.client.RestClient;

/**
 * Rung 4 (engine-harness): the M2a status join against REAL flowable-rest 6.8 — the
 * inverted DLQ-driven plan, the failed-in-subprocess roll-up, the RETRYING tier, scan
 * truncation honesty and the partial-results contract. Every fixture is seeded organically
 * over REST (never SQL); every wait is Awaitility against real engine state.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml up -d
 * Registry (application-it-search.yml): engine-a (:8081), engine-tiny (same engine,
 * dlq-scan-cap 2) and engine-down (nothing listens).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-search")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchServiceIT {

    private static final String ENGINE = "http://localhost:8081/flowable-rest/service";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    private RestClient engine;
    private final String businessKey = "it-search-" + UUID.randomUUID();

    private String parentId;
    private String childId;
    private String directFailedId;
    private String extraFailedId;
    private String retryingId;
    private String suspendedId;

    @BeforeAll
    void seedOrganically() {
        engine = EngineSeed.requireReachable(ENGINE, "");
        EngineSeed.deployIfMissing(engine, "demoFailingPayment", EngineSeed.FAILING_PAYMENT_BPMN);
        EngineSeed.deployIfMissing(engine, "demoFailingRetry", EngineSeed.FAILING_RETRY_BPMN);
        EngineSeed.deployIfMissing(engine, "demoParent", EngineSeed.PARENT_BPMN);
        EngineSeed.deployIfMissing(engine, "demoUserTask", EngineSeed.USER_TASK_BPMN);

        parentId = EngineSeed.startFailing(engine, "demoParent", businessKey);
        childId = EngineSeed.childInstanceOf(engine, parentId);
        directFailedId = EngineSeed.startFailing(engine, "demoFailingPayment", businessKey);
        // Third dead-letter source so engine-tiny (cap 2) is truncated even on a fresh CI stack.
        extraFailedId = EngineSeed.startFailing(engine, "demoFailingPayment", businessKey + "-extra");
        retryingId = EngineSeed.startFailing(engine, "demoFailingRetry", businessKey);
        suspendedId = EngineSeed.startInstance(engine, "demoUserTask", businessKey, List.of());
        EngineSeed.suspend(engine, suspendedId);
    }

    /* ---------------- the fixtures' async legs, awaited on REAL engine state ---------------- */

    private void awaitDeadLetters() {
        await().atMost(60, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(EngineSeed.deadLetterCountFor(engine, childId)).isGreaterThanOrEqualTo(1);
            assertThat(EngineSeed.deadLetterCountFor(engine, directFailedId)).isGreaterThanOrEqualTo(1);
            assertThat(EngineSeed.deadLetterCountFor(engine, extraFailedId)).isGreaterThanOrEqualTo(1);
        });
    }

    private void awaitRetrying() {
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(EngineSeed.failingTimerCountFor(engine, retryingId))
                        .isGreaterThanOrEqualTo(1));
    }

    /* ---------------- the tests ---------------- */

    @Test
    void failedOnlySearchDrivesFromTheDlqAndRollsUpTheSubprocessFailure() throws Exception {
        awaitDeadLetters();

        JsonNode body = search(Map.of(
                "engineIds", List.of("engine-a"),
                "statuses", List.of("FAILED"),
                "businessKey", businessKey));

        // Exactly the failed set for this run's businessKey: the failing CHILD, its rolled-up
        // PARENT, and the directly-failed instance. The RETRYING instance shares the
        // businessKey but has retries left — it must NOT be classified FAILED.
        assertThat(ids(body.get("rows"))).containsExactlyInAnyOrder(parentId, childId, directFailedId);

        JsonNode parent = rowOf(body, parentId);
        assertThat(parent.get("status").asText()).isEqualTo("FAILED");
        assertThat(parent.get("flags").get("failedInSubprocess").asBoolean()).isTrue();
        assertThat(parent.get("flags").get("hasDeadLetterJobs").asBoolean()).isFalse();
        assertThat(parent.get("compositeId").asText()).isEqualTo("engine-a:" + parentId);
        // The parent inherits the failure evidence from its child (deep-link target, R-UXQ-11).
        assertThat(parent.get("failureTime").isNull()).isFalse();
        assertThat(parent.get("currentActivityOrError").asText()).contains("amount % divisor");
        assertThat(parent.get("processDefinitionKey").asText()).isEqualTo("demoParent");
        assertThat(parent.get("definitionVersion").isNull()).isFalse();
        assertThat(parent.get("scopeType").asText()).isEqualTo("bpmn");

        JsonNode child = rowOf(body, childId);
        assertThat(child.get("flags").get("hasDeadLetterJobs").asBoolean()).isTrue();
        assertThat(child.get("status").asText()).isEqualTo("FAILED");
        assertThat(child.get("businessKey").asText()).isEqualTo(businessKey); // inherited

        JsonNode engineResult = body.get("perEngine").get("engine-a");
        assertThat(engineResult.get("ok").asBoolean()).isTrue();
    }

    @Test
    void retryingTierComesFromTheWithExceptionLanesNotTheDlq() throws Exception {
        awaitRetrying();

        JsonNode body = search(Map.of(
                "engineIds", List.of("engine-a"),
                "statuses", List.of("RETRYING"),
                "businessKey", businessKey));

        assertThat(ids(body.get("rows"))).containsExactly(retryingId);
        JsonNode row = rowOf(body, retryingId);
        assertThat(row.get("status").asText()).isEqualTo("RETRYING");
        assertThat(row.get("flags").get("hasFailingJobs").asBoolean()).isTrue();
        assertThat(row.get("flags").get("hasDeadLetterJobs").asBoolean()).isFalse();
        assertThat(row.get("currentActivityOrError").asText()).contains("amount % divisor");
    }

    @Test
    void mixedSearchEnrichesExactlyTheDisplayedPage() throws Exception {
        awaitDeadLetters();
        awaitRetrying();

        // No statuses → all → the MIXED plan (historic query + per-page enrichment).
        JsonNode body = search(Map.of("engineIds", List.of("engine-a"), "businessKey", businessKey));

        assertThat(ids(body.get("rows")))
                .containsExactlyInAnyOrder(parentId, childId, directFailedId, retryingId, suspendedId);

        assertThat(rowOf(body, childId).get("status").asText()).isEqualTo("FAILED");
        assertThat(rowOf(body, retryingId).get("status").asText()).isEqualTo("RETRYING");
        JsonNode suspended = rowOf(body, suspendedId);
        assertThat(suspended.get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(suspended.get("flags").get("suspended").asBoolean()).isTrue();
        // Mixed-plan semantics (ARCH §2.3): the roll-up belongs to the INVERTED plan; here
        // the parent shows ACTIVE (it IS active — waiting on the call activity).
        assertThat(rowOf(body, parentId).get("status").asText()).isEqualTo("ACTIVE");

        assertThat(body.get("perEngine").get("engine-a").get("total").asLong()).isEqualTo(5);
    }

    @Test
    void cappedDlqScanIsBadgedTruncatedNeverSilent() throws Exception {
        awaitDeadLetters(); // ≥3 dead-letter jobs on the engine — past engine-tiny's cap of 2

        JsonNode body = search(Map.of("engineIds", List.of("engine-tiny"), "statuses", List.of("FAILED")));

        JsonNode engineResult = body.get("perEngine").get("engine-tiny");
        assertThat(engineResult.get("ok").asBoolean()).isTrue();
        assertThat(engineResult.get("dlqScan").asText()).isEqualTo("truncated@2");
    }

    @Test
    void unreachableEngineDegradesToAnErrorEnvelopeInsideA200() throws Exception {
        awaitDeadLetters();

        JsonNode body = search(Map.of(
                "engineIds", List.of("engine-a", "engine-down"),
                "statuses", List.of("FAILED"),
                "businessKey", businessKey));

        JsonNode down = body.get("perEngine").get("engine-down");
        assertThat(down.get("ok").asBoolean()).isFalse();
        assertThat(down.get("error").isNull()).isFalse();
        // Partial results are the contract: the healthy engine's rows are all present.
        assertThat(body.get("perEngine").get("engine-a").get("ok").asBoolean()).isTrue();
        assertThat(ids(body.get("rows"))).containsExactlyInAnyOrder(parentId, childId, directFailedId);
    }

    /* ---------------- plumbing ---------------- */

    private JsonNode search(Map<String, Object> request) throws Exception {
        ResponseEntity<String> response = rest.postForEntity("/api/search", request, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody());
    }

    private static List<String> ids(JsonNode rows) {
        List<String> ids = new java.util.ArrayList<>();
        rows.forEach(r -> ids.add(r.get("processInstanceId").asText()));
        return ids;
    }

    private static JsonNode rowOf(JsonNode body, String processInstanceId) {
        for (JsonNode row : body.get("rows")) {
            if (processInstanceId.equals(row.get("processInstanceId").asText())) {
                return row;
            }
        }
        throw new AssertionError("row not found for instance " + processInstanceId);
    }
}
