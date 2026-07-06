package io.inspector.aggregate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.EngineSeed;
import io.inspector.support.NoDbTestSupport;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * Rung 4, cross-version: the M2a join arc on Flowable 7.1 (Jakarta wire shapes) — the
 * inverted plan, subprocess roll-up and RETRYING tier must behave identically to 6.8.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml --profile flowable-7 up -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_7_PASSWORD=test")
@ActiveProfiles("it7")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// M4: docker-free profile — DB autoconfig excluded, repositories mocked (NoDbTestSupport).
@Import(NoDbTestSupport.class)
class Search7IT {

    private static final String ENGINE = "http://localhost:8083/flowable-rest/service";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    private RestClient engine;
    private final String businessKey = "it7-search-" + UUID.randomUUID();
    private String parentId;
    private String childId;
    private String retryingId;

    @BeforeAll
    void seedOrganically() {
        rest = rest.withBasicAuth("admin", "dev"); // M4: the BFF now requires auth on every /api route
        engine = EngineSeed.requireReachable(ENGINE, "--profile flowable-7");
        EngineSeed.deployIfMissing(engine, "demoFailingPayment", EngineSeed.FAILING_PAYMENT_BPMN);
        EngineSeed.deployIfMissing(engine, "demoFailingRetry", EngineSeed.FAILING_RETRY_BPMN);
        EngineSeed.deployIfMissing(engine, "demoParent", EngineSeed.PARENT_BPMN);
        parentId = EngineSeed.startFailing(engine, "demoParent", businessKey);
        childId = EngineSeed.childInstanceOf(engine, parentId);
        retryingId = EngineSeed.startFailing(engine, "demoFailingRetry", businessKey);
    }

    @Test
    void invertedPlanAndRollupWorkOnFlowable7() throws Exception {
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(EngineSeed.deadLetterCountFor(engine, childId))
                        .isGreaterThanOrEqualTo(1));

        JsonNode body = search(Map.of(
                "engineIds", List.of("engine-7"),
                "statuses", List.of("FAILED"),
                "businessKey", businessKey));

        assertThat(body.get("perEngine").get("engine-7").get("ok").asBoolean()).isTrue();
        JsonNode parent = rowOf(body, parentId);
        assertThat(parent.get("status").asText()).isEqualTo("FAILED");
        assertThat(parent.get("flags").get("failedInSubprocess").asBoolean()).isTrue();
        assertThat(rowOf(body, childId).get("flags").get("hasDeadLetterJobs").asBoolean())
                .isTrue();
    }

    @Test
    void retryingTierWorksOnFlowable7() throws Exception {
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(EngineSeed.failingTimerCountFor(engine, retryingId))
                        .isGreaterThanOrEqualTo(1));

        JsonNode body = search(Map.of(
                "engineIds", List.of("engine-7"),
                "statuses", List.of("RETRYING"),
                "businessKey", businessKey));

        JsonNode row = rowOf(body, retryingId);
        assertThat(row.get("status").asText()).isEqualTo("RETRYING");
        assertThat(row.get("flags").get("hasFailingJobs").asBoolean()).isTrue();
    }

    @Test
    void m2bFiltersComposeOnFlowable7() throws Exception {
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(EngineSeed.deadLetterCountFor(engine, childId))
                        .isGreaterThanOrEqualTo(1));
        String hourAgo = java.time.Instant.now().minusSeconds(3600).toString();

        // Native businessKeyLike pushdown + BFF-side failure window and error text, ANDed:
        // the failing child and its rolled-up parent survive all three.
        JsonNode body = search(Map.of(
                "engineIds",
                List.of("engine-7"),
                "statuses",
                List.of("FAILED"),
                "businessKeyLike",
                businessKey,
                "failureTimeAfter",
                hourAgo,
                "errorText",
                "amount % divisor"));

        assertThat(body.get("perEngine").get("engine-7").get("ok").asBoolean()).isTrue();
        assertThat(body.get("rows")).hasSize(2);
        assertThat(rowOf(body, parentId).get("flags").get("failedInSubprocess").asBoolean())
                .isTrue();
        assertThat(rowOf(body, childId).get("flags").get("hasDeadLetterJobs").asBoolean())
                .isTrue();
        assertThat(body.get("statusCounts").get("FAILED").asLong()).isEqualTo(2);
    }

    private JsonNode search(Map<String, Object> request) throws Exception {
        ResponseEntity<String> response = rest.postForEntity("/api/search", request, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody());
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
