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
// M4: docker-free profile — DB autoconfig excluded, repositories mocked (NoDbTestSupport).
@Import(NoDbTestSupport.class)
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
    private String varTaggedId;

    @BeforeAll
    void seedOrganically() {
        rest = rest.withBasicAuth("admin", "dev"); // M4: the BFF now requires auth on every /api route
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
        // M2b: inert instance carrying a run-unique STRING variable for the like-search arc.
        varTaggedId = EngineSeed.startInstance(
                engine,
                "demoUserTask",
                businessKey + "-vartag",
                List.of(Map.of("name", "customerName", "type", "string", "value", "Customer " + businessKey)));
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

    /* ---------------- M2b: search additions ---------------- */

    @Test
    void businessKeyLikeFindsEveryInstanceSharingTheKeyFragment() throws Exception {
        // Substring (no wildcards) — the BFF wraps it %…% and pushes it down natively.
        JsonNode body = search(Map.of("engineIds", List.of("engine-a"), "businessKeyLike", businessKey));

        assertThat(ids(body.get("rows")))
                .containsExactlyInAnyOrder(
                        parentId, childId, directFailedId, extraFailedId, retryingId, suspendedId, varTaggedId);
    }

    @Test
    void variableLikeSearchRetrievesTheTaggedInstance() throws Exception {
        JsonNode body = search(Map.of(
                "engineIds",
                List.of("engine-a"),
                "variables",
                List.of(Map.of("name", "customerName", "operation", "like", "value", "%" + businessKey + "%"))));

        assertThat(ids(body.get("rows"))).containsExactly(varTaggedId);
    }

    @Test
    void failureTimeWindowFiltersOnDeadLetterCreateTimeNotStartTime() throws Exception {
        awaitDeadLetters();
        String hourAgo = java.time.Instant.now().minusSeconds(3600).toString();

        // "failed in the last hour" — the naturally dead-lettered instances are inside it.
        JsonNode recent = search(Map.of(
                "engineIds",
                List.of("engine-a"),
                "statuses",
                List.of("FAILED"),
                "businessKey",
                businessKey,
                "failureTimeAfter",
                hourAgo,
                "sortBy",
                "failureTime"));
        assertThat(ids(recent.get("rows"))).containsExactlyInAnyOrder(parentId, childId, directFailedId);
        // failureTime sort: every FAILED row carries evidence, ordered newest first.
        List<java.time.Instant> failureTimes = new java.util.ArrayList<>();
        for (JsonNode r : recent.get("rows")) {
            assertThat(r.get("failureTime").isNull()).isFalse();
            failureTimes.add(java.time.OffsetDateTime.parse(r.get("failureTime").asText())
                    .toInstant());
        }
        assertThat(failureTimes).isSortedAccordingTo(java.util.Comparator.reverseOrder());

        // ...and nothing failed BEFORE that window opened: the same search inverted is empty,
        // even though the instances themselves started long before they dead-lettered.
        JsonNode old = search(Map.of(
                "engineIds",
                List.of("engine-a"),
                "statuses",
                List.of("FAILED"),
                "businessKey",
                businessKey,
                "failureTimeBefore",
                hourAgo));
        assertThat(old.get("rows")).isEmpty();
        assertThat(old.get("perEngine").get("engine-a").get("ok").asBoolean()).isTrue();
    }

    @Test
    void errorTextFiltersBeforeRollupSoAFilteredChildNeverSurfacesItsParent() throws Exception {
        awaitDeadLetters();

        JsonNode matching = search(Map.of(
                "engineIds",
                List.of("engine-a"),
                "statuses",
                List.of("FAILED"),
                "businessKey",
                businessKey,
                "errorText",
                "amount % divisor"));
        assertThat(ids(matching.get("rows"))).containsExactlyInAnyOrder(parentId, childId, directFailedId);

        // A non-matching error text removes the CHILD from the scan leg — and with it the
        // rolled-up parent (the filter bites before root resolution).
        JsonNode nonMatching = search(Map.of(
                "engineIds",
                List.of("engine-a"),
                "statuses",
                List.of("FAILED"),
                "businessKey",
                businessKey,
                "errorText",
                "connection refused"));
        assertThat(nonMatching.get("rows")).isEmpty();
    }

    @Test
    void currentActivityMatchesUnfinishedActivityIdOrNameCaseInsensitively() throws Exception {
        // "viewOrd" ⊂ "reviewOrder" (id) and "Review order" (name) — only the user-task
        // instance sits at that activity; the parent waits at its call activity.
        JsonNode body = search(
                Map.of("engineIds", List.of("engine-a"), "businessKey", businessKey, "currentActivity", "viewOrd"));

        assertThat(ids(body.get("rows"))).containsExactly(suspendedId);
    }

    @Test
    void statusCountsFacetCountsCandidatesBeforeTheStatusPredicate() throws Exception {
        awaitDeadLetters();
        awaitRetrying();

        // Mixed plan over this run's businessKey: the facet sees every candidate.
        JsonNode mixed = search(Map.of("engineIds", List.of("engine-a"), "businessKey", businessKey));
        JsonNode counts = mixed.get("statusCounts");
        assertThat(counts.get("FAILED").asLong()).isEqualTo(2); // child + direct
        assertThat(counts.get("RETRYING").asLong()).isEqualTo(1);
        assertThat(counts.get("SUSPENDED").asLong()).isEqualTo(1);
        assertThat(counts.get("ACTIVE").asLong()).isEqualTo(1); // the parent (no roll-up in mixed)

        // Inverted plan: candidates are the failure lanes only — FAILED counts the rolled-up
        // parent too, and statuses the plan never observed have NO key (never a fake zero).
        JsonNode inverted = search(Map.of(
                "engineIds", List.of("engine-a"),
                "statuses", List.of("FAILED"),
                "businessKey", businessKey));
        assertThat(inverted.get("statusCounts").get("FAILED").asLong()).isEqualTo(3);
        assertThat(inverted.get("statusCounts").get("ACTIVE")).isNull();
    }

    /* ---------------- M3 stragglers: definitionVersion + signatureHash ---------------- */

    @Test
    void definitionVersionNarrowsToTheConcreteDeployedVersion() throws Exception {
        awaitDeadLetters();

        // Read the actual version off an unfiltered hit first — the shared dev engine's
        // version counter moves across runs, so the fixture's version is engine truth.
        JsonNode unfiltered = search(Map.of(
                "engineIds",
                List.of("engine-a"),
                "statuses",
                List.of("FAILED"),
                "businessKey",
                businessKey,
                "processDefinitionKey",
                "demoFailingPayment"));
        assertThat(ids(unfiltered.get("rows"))).containsExactlyInAnyOrder(childId, directFailedId);
        int version = rowOf(unfiltered, directFailedId).get("definitionVersion").asInt();

        JsonNode exact = search(Map.of(
                "engineIds",
                List.of("engine-a"),
                "statuses",
                List.of("FAILED"),
                "businessKey",
                businessKey,
                "processDefinitionKey",
                "demoFailingPayment",
                "definitionVersion",
                version));
        assertThat(ids(exact.get("rows"))).containsExactlyInAnyOrder(childId, directFailedId);
        assertThat(rowOf(exact, directFailedId).get("definitionVersion").asInt())
                .isEqualTo(version);

        // A version that is not deployed is an honestly EMPTY slice — never unfiltered rows.
        JsonNode phantom = search(Map.of(
                "engineIds",
                List.of("engine-a"),
                "statuses",
                List.of("FAILED"),
                "processDefinitionKey",
                "demoFailingPayment",
                "definitionVersion",
                999999));
        assertThat(phantom.get("rows")).isEmpty();
        assertThat(phantom.get("perEngine").get("engine-a").get("ok").asBoolean())
                .isTrue();

        // …and a bare version without its key is the caller's mistake: 400, not a scan.
        ResponseEntity<String> bare = rest.postForEntity("/api/search", Map.of("definitionVersion", 3), String.class);
        assertThat(bare.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bare.getBody()).contains("processDefinitionKey");
    }

    @Test
    @SuppressWarnings("unchecked")
    void signatureHashDrillsFromATriageCardIntoExactlyItsInstances() throws Exception {
        awaitDeadLetters();

        // Engine truth: the dead-letter job's snippet AND its stacktrace-refined signature
        // (what a triage card carries after refinement) — computed with the SAME normative
        // normalizer the BFF uses (R-SEM-03: the hash is the binding contract).
        Map<String, Object> dlqPage = engine.get()
                .uri("/management/deadletter-jobs?processInstanceId=" + directFailedId)
                .retrieve()
                .body(Map.class);
        Map<String, Object> job = ((List<Map<String, Object>>) dlqPage.get("data")).get(0);
        String snippetHash = io.inspector.triage.ErrorSignatureNormalizer.normalize(
                        String.valueOf(job.get("exceptionMessage")))
                .hash();
        String stacktrace = engine.get()
                .uri("/management/deadletter-jobs/" + job.get("id") + "/exception-stacktrace")
                .retrieve()
                .body(String.class);
        String refinedHash = io.inspector.triage.ErrorSignatureNormalizer.normalize(stacktrace)
                .hash();
        assertThat(refinedHash).isNotEqualTo(snippetHash); // the bridge is load-bearing

        // Snippet-level hash: direct match on the scan legs.
        JsonNode bySnippet = search(Map.of(
                "engineIds",
                List.of("engine-a"),
                "statuses",
                List.of("FAILED"),
                "businessKey",
                businessKey,
                "signatureHash",
                snippetHash));
        assertThat(ids(bySnippet.get("rows"))).containsExactlyInAnyOrder(parentId, childId, directFailedId);

        // Refined hash (the triage drill): matches via the one-representative bridge.
        JsonNode byRefined = search(Map.of(
                "engineIds",
                List.of("engine-a"),
                "statuses",
                List.of("FAILED"),
                "businessKey",
                businessKey,
                "signatureHash",
                refinedHash));
        assertThat(ids(byRefined.get("rows"))).containsExactlyInAnyOrder(parentId, childId, directFailedId);

        // A foreign hash filters the child out on the scan leg — and with it the rolled-up
        // parent (same before-roll-up semantics as errorText).
        JsonNode foreign = search(Map.of(
                "engineIds", List.of("engine-a"),
                "statuses", List.of("FAILED"),
                "businessKey", businessKey,
                "signatureHash", "0".repeat(64)));
        assertThat(foreign.get("rows")).isEmpty();
    }

    @Test
    void criteriaEchoAndCurlCompileTheAppliedFilters() throws Exception {
        JsonNode body = search(Map.of(
                "engineIds",
                List.of("engine-a"),
                "statuses",
                List.of("FAILED", "RETRYING"),
                "businessKey",
                businessKey,
                "errorText",
                "divisor"));

        List<String> echo = new java.util.ArrayList<>();
        body.get("criteriaEcho").forEach(line -> echo.add(line.asText()));
        assertThat(echo)
                .contains(
                        "Engines: engine-a",
                        "Status: FAILED or RETRYING",
                        "Business key: exactly '" + businessKey + "'",
                        "Error text: contains 'divisor'");

        String curl = body.get("curl").asText();
        assertThat(curl).startsWith("curl -X POST 'http://");
        assertThat(curl).contains("/api/search");
        assertThat(curl).contains(businessKey);
        assertThat(curl).contains("\"statuses\":[\"FAILED\",\"RETRYING\"]");
    }

    @Test
    void unparseableFailureWindowIsA400NotA500() {
        ResponseEntity<String> response =
                rest.postForEntity("/api/search", Map.of("failureTimeAfter", "yesterday-ish"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("failureTimeAfter");
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
