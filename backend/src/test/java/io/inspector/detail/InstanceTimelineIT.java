package io.inspector.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.EngineSeed;
import io.inspector.support.NoDbTestSupport;
import java.util.ArrayList;
import java.util.List;
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
 * Rung 4 (engine-harness): the timeline call-activity <b>sub-lane recursion</b> and the
 * <b>live FAILED/dead-letter join</b> against REAL flowable-rest 6.8 — the iron rule forbids
 * mocking the FAILED join, so it is proven here on the wire (the pure recursion caps + cycle
 * guard are rung-1 in {@link InstanceTimelineServiceTest}). Seeds demoParent → a failing
 * demoFailingPayment child, then asserts the child's activities nest under the call-activity
 * node and the dead-lettered activity carries {@code liveJobState=FAILED} — even though its
 * async history row rolled back (the phantom-node union).
 *
 * <p>Requires: docker compose -f docker/docker-compose.dev.yml up -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-search")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(NoDbTestSupport.class)
class InstanceTimelineIT {

    private static final String ENGINE =
            "http://localhost:" + System.getenv().getOrDefault("PI_ENGINE_A_PORT", "8081") + "/flowable-rest/service";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    private RestClient engine;
    private final String businessKey = "it-timeline-" + UUID.randomUUID();
    private String parentId;
    private String childId;

    @BeforeAll
    void seedOrganically() {
        rest = rest.withBasicAuth("admin", "dev");
        engine = EngineSeed.requireReachable(ENGINE, "");
        EngineSeed.deployIfMissing(engine, "demoParent", EngineSeed.PARENT_BPMN);
        EngineSeed.deployIfMissing(engine, "demoFailingPayment", EngineSeed.FAILING_PAYMENT_BPMN);

        parentId = EngineSeed.startFailing(engine, "demoParent", businessKey);
        childId = EngineSeed.childInstanceOf(engine, parentId);

        // The dead-letter is REAL engine state — bound the wait, never sleep, never poll a mutation.
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(EngineSeed.deadLetterCountFor(engine, childId))
                        .isGreaterThanOrEqualTo(1));
    }

    @Test
    void timelineNestsTheChildActivitiesUnderTheCallActivity_respectingCaps() {
        JsonNode callActivity = callActivityOf(get("/api/instances/engine-a/" + parentId + "/timeline"));

        assertThat(callActivity.get("activityType").asText()).isEqualTo("callActivity");
        assertThat(callActivity.get("calledProcessInstanceId").asText())
                .as("the sub-lane link to the child instance")
                .isEqualTo(childId);

        JsonNode children = callActivity.get("children");
        assertThat(children)
                .as("the child instance's activities nest in-place: %s", callActivity)
                .isNotEmpty();
        for (JsonNode child : children) {
            assertThat(child.get("activityId").asText(null))
                    .as("every nested node is a real activity row")
                    .isNotBlank();
        }
        // A two-node tree well under depth 10 / breadth 50 — no cap is tripped.
        assertThat(callActivity.get("isCapped").asBoolean()).isFalse();
    }

    @Test
    void theFailingChildActivityCarriesTheLiveDeadLetterAnnotation() {
        // @BeforeAll already awaited the dead-letter, so the read-time join is deterministic here.
        JsonNode callActivity = callActivityOf(get("/api/instances/engine-a/" + parentId + "/timeline"));

        assertThat(liveStatesUnder(callActivity.get("children")))
                .as("the dead-lettered child activity is annotated FAILED through the sub-lane: %s", callActivity)
                .contains("FAILED");
    }

    @Test
    void theChildTimelineDirectlyAnnotatesTheDeadLetteredActivity() {
        // Queried on its own, the child timeline still surfaces the failure — synthesized from
        // the live lanes if the async history row rolled back (phantom-node union).
        JsonNode body = get("/api/instances/engine-a/" + childId + "/timeline");

        assertThat(liveStatesUnder(body.get("activities")))
                .as("FAILED annotation on the child's own timeline: %s", body)
                .contains("FAILED");
    }

    /* ================= plumbing ================= */

    private JsonNode callActivityOf(JsonNode timeline) {
        for (JsonNode activity : timeline.get("activities")) {
            if ("callPayment".equals(activity.get("activityId").asText(null))) {
                return activity;
            }
        }
        throw new AssertionError("call activity 'callPayment' not on the parent timeline: " + timeline);
    }

    /** Every {@code liveJobState} in a node forest, recursing into sub-lanes; healthy nodes omit it. */
    private List<String> liveStatesUnder(JsonNode nodes) {
        List<String> states = new ArrayList<>();
        if (nodes == null) return states;
        for (JsonNode node : nodes) {
            JsonNode state = node.get("liveJobState");
            if (state != null && !state.isNull()) states.add(state.asText());
            states.addAll(liveStatesUnder(node.get("children")));
        }
        return states;
    }

    private JsonNode get(String path) {
        ResponseEntity<String> response = rest.getForEntity(path, String.class);
        assertThat(response.getStatusCode())
                .as("GET %s -> %s", path, response.getBody())
                .isEqualTo(HttpStatus.OK);
        try {
            return mapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new AssertionError("unparseable response from " + path, e);
        }
    }
}
