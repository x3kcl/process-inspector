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
 * Rung 4, pre-cliff: the M2a join on Flowable 6.3.1. Two version hazards are pinned here:
 * the roll-up + inverted plan on the 6.3 wire shapes, and the SUSPENDED enrichment
 * fallback — 6.3 silently IGNORES {@code processInstanceIds} on the runtime query (the
 * unknown field is dropped and the result is unfiltered), so the bulk suspended check must
 * detect that and fall back to per-id GETs instead of joining foreign rows.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml --profile legacy up -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_LEGACY_PASSWORD=test")
@ActiveProfiles("it-legacy")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchLegacyIT {

    private static final String ENGINE = "http://localhost:8084/flowable-rest/service";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    private RestClient engine;
    private final String businessKey = "it-legacy-search-" + UUID.randomUUID();
    private String parentId;
    private String childId;
    private String suspendedId;

    @BeforeAll
    void seedOrganically() {
        engine = EngineSeed.requireReachable(ENGINE, "--profile legacy");
        EngineSeed.deployIfMissing(engine, "demoFailingPayment", EngineSeed.FAILING_PAYMENT_BPMN);
        EngineSeed.deployIfMissing(engine, "demoParent", EngineSeed.PARENT_BPMN);
        EngineSeed.deployIfMissing(engine, "demoUserTask", EngineSeed.USER_TASK_BPMN);
        parentId = EngineSeed.startFailing(engine, "demoParent", businessKey);
        childId = EngineSeed.childInstanceOf(engine, parentId);
        suspendedId = EngineSeed.startInstance(engine, "demoUserTask", businessKey, List.of());
        EngineSeed.suspend(engine, suspendedId);
    }

    @Test
    void invertedPlanAndRollupWorkOnTheLegacyWireShapes() throws Exception {
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(EngineSeed.deadLetterCountFor(engine, childId))
                        .isGreaterThanOrEqualTo(1));

        JsonNode body = search(Map.of(
                "engineIds", List.of("engine-legacy"),
                "statuses", List.of("FAILED"),
                "businessKey", businessKey));

        assertThat(body.get("perEngine").get("engine-legacy").get("ok").asBoolean())
                .isTrue();
        JsonNode parent = rowOf(body, parentId);
        assertThat(parent.get("status").asText()).isEqualTo("FAILED");
        assertThat(parent.get("flags").get("failedInSubprocess").asBoolean()).isTrue();
        // 6.3 serializes no processDefinition key/version on historic rows — both must be
        // derived from processDefinitionId, not blank.
        assertThat(parent.get("processDefinitionKey").asText()).isEqualTo("demoParent");
        assertThat(parent.get("definitionVersion").isNull()).isFalse();
        assertThat(rowOf(body, childId).get("flags").get("hasDeadLetterJobs").asBoolean())
                .isTrue();
    }

    @Test
    void suspendedEnrichmentSurvivesTheIgnoredProcessInstanceIdsFilter() throws Exception {
        // Mixed plan (no statuses). On 6.3 the bulk runtime query returns UNFILTERED data;
        // trusting it would mislabel rows — the fallback must yield the true flags.
        JsonNode body = search(Map.of("engineIds", List.of("engine-legacy"), "businessKey", businessKey));

        JsonNode suspended = rowOf(body, suspendedId);
        assertThat(suspended.get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(suspended.get("flags").get("suspended").asBoolean()).isTrue();
        // And the open-but-not-suspended parent must NOT inherit a foreign suspended flag.
        assertThat(rowOf(body, parentId).get("flags").get("suspended").asBoolean())
                .isFalse();
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
