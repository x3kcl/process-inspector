package io.inspector.detail;

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
 * Rung 4 (engine-harness): the M3 detail-data endpoints and the omnibox resolver against
 * REAL flowable-rest 6.8 — GET /api/resolve, vitals, diagram XML proxying, the TYPED
 * variable ledger, the four job lanes, hierarchy and timeline. Registry = it-search
 * (engine-a, engine-tiny = same engine, engine-down = unreachable), so the resolver's
 * cross-engine disambiguation and the N-of-M reachability banner are proven too.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml up -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-search")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(NoDbTestSupport.class)
class DetailResolveIT {

    private static final String ENGINE =
            "http://localhost:" + System.getenv().getOrDefault("PI_ENGINE_A_PORT", "8081") + "/flowable-rest/service";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    private RestClient engine;
    private final String businessKey = "it-detail-" + UUID.randomUUID();

    private String parentId;
    private String childId;
    private String completedId;
    private String userTaskId;

    @BeforeAll
    void seedOrganically() {
        rest = rest.withBasicAuth("admin", "dev");
        engine = EngineSeed.requireReachable(ENGINE, "");
        EngineSeed.deployIfMissing(engine, "demoParent", EngineSeed.PARENT_BPMN);
        EngineSeed.deployIfMissing(engine, "demoFailingPayment", EngineSeed.FAILING_PAYMENT_BPMN);
        EngineSeed.deployIfMissing(engine, "demoUserTask", EngineSeed.USER_TASK_BPMN);
        EngineSeed.deployIfMissing(engine, "demoOrder", EngineSeed.ORDER_BPMN);

        // The full troubleshooting arc on one businessKey: a failing call-activity child
        // under demoParent, a COMPLETED order carrying typed variables, and a parked user
        // task (live executions for the ledger's runtime path).
        parentId = EngineSeed.startFailing(engine, "demoParent", businessKey);
        childId = EngineSeed.childInstanceOf(engine, parentId);
        completedId = EngineSeed.startInstance(
                engine,
                "demoOrder",
                businessKey + "-done",
                List.of(
                        Map.of("name", "amount", "type", "integer", "value", 250),
                        Map.of("name", "customerName", "type", "string", "value", "Ada Lovelace")));
        userTaskId = EngineSeed.startInstance(
                engine,
                "demoUserTask",
                businessKey + "-task",
                List.of(
                        Map.of("name", "amount", "type", "integer", "value", 100),
                        Map.of("name", "approved", "type", "boolean", "value", false)));

        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(EngineSeed.deadLetterCountFor(engine, childId))
                        .isGreaterThanOrEqualTo(1));
    }

    /* ================= Task 2: GET /api/resolve ================= */

    @Test
    void resolveFindsDemoParentByItsBusinessKeyAcrossReachableEngines() {
        JsonNode body = get("/api/resolve?q=" + businessKey);

        assertThat(body.get("query").asText()).isEqualTo(businessKey);
        // engine-a and engine-tiny are the SAME physical engine — the key resolves on
        // both (ids are only engine-unique, R-SEM-04: never auto-navigate on ambiguity)…
        List<JsonNode> byKey = matchesOf(body, "BUSINESS_KEY");
        assertThat(byKey).isNotEmpty();
        assertThat(byKey.stream().map(m -> m.get("engineId").asText()).distinct())
                .containsExactlyInAnyOrder("engine-a", "engine-tiny");
        JsonNode parentMatch = byKey.stream()
                .filter(m -> m.get("engineId").asText().equals("engine-a")
                        && m.get("processInstanceId").asText().equals(parentId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("demoParent not resolved by business key: " + body));
        assertThat(parentMatch.get("compositeId").asText()).isEqualTo("engine-a:" + parentId);
        assertThat(parentMatch.get("definitionKey").asText()).isEqualTo("demoParent");
        // …with the derived flags: the parent's failure is in its call-activity CHILD.
        assertThat(parentMatch.get("flags").get("failedInSubprocess").asBoolean())
                .isTrue();
        assertThat(parentMatch.get("status").asText()).isEqualTo("FAILED");

        // The reachability banner data: engine-down is an envelope entry, never a failure.
        assertThat(body.get("perEngine").get("engine-a").get("ok").asBoolean()).isTrue();
        assertThat(body.get("perEngine").get("engine-down").get("ok").asBoolean())
                .isFalse();
    }

    @Test
    void resolveOrdersProcessInstanceIdBeforeBusinessKeyAndDerivesFlags() {
        JsonNode body = get("/api/resolve?q=" + childId);

        List<JsonNode> instances = matchesOf(body, "PROCESS_INSTANCE");
        assertThat(instances).hasSize(2); // engine-a + engine-tiny (same engine twice)
        JsonNode match = instances.get(0);
        assertThat(match.get("processInstanceId").asText()).isEqualTo(childId);
        assertThat(match.get("businessKey").asText()).isEqualTo(businessKey); // inherited
        assertThat(match.get("flags").get("hasDeadLetterJobs").asBoolean()).isTrue();
        assertThat(match.get("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void resolveHandlesCompositeIdsTaskIdsAndJobIdsInTheNormativeOrder() {
        // Composite engine:id — short-circuits to THAT engine only.
        JsonNode composite = get("/api/resolve?q=engine-a:" + parentId);
        assertThat(matchesOf(composite, "PROCESS_INSTANCE")).hasSize(1);
        assertThat(composite.get("perEngine").has("engine-tiny")).isFalse();

        // A raw runtime TASK id resolves to its owning instance.
        String taskId = engineTaskIdFor(userTaskId);
        JsonNode task = get("/api/resolve?q=" + taskId);
        List<JsonNode> taskMatches = matchesOf(task, "TASK");
        assertThat(taskMatches).isNotEmpty();
        assertThat(taskMatches.get(0).get("processInstanceId").asText()).isEqualTo(userTaskId);
        assertThat(taskMatches.get(0).get("matchedId").asText()).isEqualTo(taskId);

        // A raw dead-letter JOB id resolves to the failing child instance.
        String jobId = EngineSeed.deadLetterJobIdFor(engine, childId);
        JsonNode job = get("/api/resolve?q=" + jobId);
        List<JsonNode> jobMatches = matchesOf(job, "JOB");
        assertThat(jobMatches).isNotEmpty();
        assertThat(jobMatches.get(0).get("processInstanceId").asText()).isEqualTo(childId);
    }

    @Test
    void resolveOfGarbageIsAnExplicitEmptyNeverAnError() {
        JsonNode body = get("/api/resolve?q=no-such-thing-" + UUID.randomUUID());
        assertThat(body.get("matches")).isEmpty();
        assertThat(body.get("perEngine").get("engine-a").get("ok").asBoolean()).isTrue();

        ResponseEntity<String> blank = rest.getForEntity("/api/resolve?q=", String.class);
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /* ================= Task 3: vitals ================= */

    @Test
    void vitalsCarryDefinitionVersionFlagsAndTheWhyStuckStrip() {
        JsonNode child = get("/api/instances/engine-a/" + childId);

        assertThat(child.get("compositeId").asText()).isEqualTo("engine-a:" + childId);
        assertThat(child.get("definitionKey").asText()).isEqualTo("demoFailingPayment");
        assertThat(child.get("definitionVersion").isNull()).isFalse();
        assertThat(child.get("businessKey").asText()).isEqualTo(businessKey);
        assertThat(child.get("superProcessInstanceId").asText()).isEqualTo(parentId);
        assertThat(child.get("status").asText()).isEqualTo("FAILED");
        assertThat(child.get("flags").get("hasDeadLetterJobs").asBoolean()).isTrue();

        JsonNode whyStuck = child.get("whyStuck");
        assertThat(whyStuck.isNull())
                .as("a FAILED instance explains itself: %s", child)
                .isFalse();
        assertThat(whyStuck.get("exceptionFirstLine").asText()).contains("amount % divisor");
        assertThat(whyStuck.get("deadLetterJobs").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(whyStuck.get("failureTime").asText()).isNotBlank();

        // TS-DET-12: engine-a configures a telemetry template — the rendered deep link
        // carries this instance; engines without a template serialize NO field at all.
        assertThat(child.get("telemetryUrl").asText())
                .startsWith("https://logs.example/discover?pi=" + childId)
                .contains("bk=" + businessKey);
        JsonNode tiny = get("/api/instances/engine-tiny/" + childId);
        assertThat(tiny.has("telemetryUrl")).isFalse();

        // The parent is ACTIVE at its call activity — no why-stuck of its own, but the
        // subprocess flag is on and the current activity names where it waits.
        JsonNode parent = get("/api/instances/engine-a/" + parentId);
        assertThat(parent.get("flags").get("failedInSubprocess").asBoolean()).isTrue();
        assertThat(parent.get("currentActivities").get(0).get("activityId").asText())
                .isEqualTo("callPayment");

        // COMPLETED instances render vitals (historic-first), never 404.
        JsonNode completed = get("/api/instances/engine-a/" + completedId);
        assertThat(completed.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.get("endTime").isNull()).isFalse();
        assertThat(completed.get("durationMs").isNull()).isFalse();

        // …and a genuinely unknown instance is a 404, not an empty page.
        ResponseEntity<String> missing = rest.getForEntity("/api/instances/engine-a/no-such-instance", String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /* ================= Task 3: diagram ================= */

    @Test
    void diagramProxiesTheDeployedBpmnXmlWithMarkerIdSets() throws Exception {
        JsonNode body = get("/api/instances/engine-a/" + childId + "/diagram");

        String xml = body.get("xml").asText();
        assertThat(xml).contains("<definitions").contains("id=\"demoFailingPayment\"");
        // Valid XML: a namespace-aware parse must succeed — the UI feeds this to bpmn-js.
        javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        org.w3c.dom.Document doc = dbf.newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(doc.getDocumentElement().getLocalName()).isEqualTo("definitions");

        // The dead-lettered service task is both still-active (token) and failed (badge).
        assertThat(jsonList(body.get("activeActivityIds"))).contains("chargePayment");
        assertThat(jsonList(body.get("deadLetterActivityIds"))).contains("chargePayment");
    }

    /* ================= Task 3: the TYPED variable ledger ================= */

    @Test
    void variablesAreATypedLedgerNeverABlindJsonMap() {
        JsonNode body = get("/api/instances/engine-a/" + userTaskId + "/variables");

        assertThat(body.get("source").asText()).isEqualTo("RUNTIME");
        JsonNode amount = variableRow(body.get("processVariables"), "amount");
        assertThat(amount.get("type").asText()).isEqualTo("integer");
        assertThat(amount.get("value").isIntegralNumber())
                .as("an integer stays a JSON number — string-ifying is the banned anti-pattern")
                .isTrue();
        assertThat(amount.get("value").asInt()).isEqualTo(100);
        assertThat(amount.get("truncated").asBoolean()).isFalse();

        JsonNode approved = variableRow(body.get("processVariables"), "approved");
        assertThat(approved.get("type").asText()).isEqualTo("boolean");
        assertThat(approved.get("value").isBoolean()).isTrue();

        // The on-demand full-value fetch serves the same typed row.
        JsonNode full = get("/api/instances/engine-a/" + userTaskId + "/variables/amount");
        assertThat(full.get("type").asText()).isEqualTo("integer");
        assertThat(full.get("value").asInt()).isEqualTo(100);

        // COMPLETED instances serve the historic projection — typed, never a 404.
        JsonNode historic = get("/api/instances/engine-a/" + completedId + "/variables");
        assertThat(historic.get("source").asText()).isEqualTo("HISTORIC");
        JsonNode customer = variableRow(historic.get("processVariables"), "customerName");
        assertThat(customer.get("type").asText()).isEqualTo("string");
        assertThat(customer.get("value").asText()).isEqualTo("Ada Lovelace");
        assertThat(variableRow(historic.get("processVariables"), "amount")
                        .get("value")
                        .isIntegralNumber())
                .isTrue();
    }

    /* ================= Task 3: the four job lanes ================= */

    @Test
    void jobsComeSegregatedIntoTheFourLanesWithStacktraceOnExpand() {
        JsonNode body = get("/api/instances/engine-a/" + childId + "/jobs");

        assertThat(body.get("deadLetter")).isNotEmpty();
        JsonNode deadLetterJob = body.get("deadLetter").get(0);
        assertThat(deadLetterJob.get("lane").asText()).isEqualTo("DEADLETTER");
        assertThat(deadLetterJob.get("exceptionMessage").asText()).contains("amount % divisor");
        assertThat(deadLetterJob.get("retries").isNull()).isFalse();
        // The healthy lanes are present AND empty — the lane is the diagnosis.
        assertThat(body.get("executable")).isEmpty();
        assertThat(body.get("suspended")).isEmpty();

        // Stacktrace on expand: the dead-letter lane's plain-text stacktrace names the cause.
        String jobId = deadLetterJob.get("id").asText();
        ResponseEntity<String> stacktrace = rest.getForEntity(
                "/api/instances/engine-a/" + childId + "/jobs/" + jobId + "/stacktrace?lane=DEADLETTER", String.class);
        assertThat(stacktrace.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stacktrace.getBody()).contains("ArithmeticException");

        ResponseEntity<String> badLane = rest.getForEntity(
                "/api/instances/engine-a/" + childId + "/jobs/" + jobId + "/stacktrace?lane=BOGUS", String.class);
        assertThat(badLane.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /* ================= Task 3: tasks — historic ∪ runtime ================= */

    @Test
    void tasksListTheParkedUserTaskLive_andCompletedInstancesAnswerEmpty() {
        JsonNode body = get("/api/instances/engine-a/" + userTaskId + "/tasks");

        assertThat(body.get("tasks")).hasSize(1);
        assertThat(body.get("truncated").asBoolean()).isFalse();
        JsonNode task = body.get("tasks").get(0);
        assertThat(task.get("name").asText()).isEqualTo("Review order");
        assertThat(task.get("taskDefinitionKey").asText()).isEqualTo("reviewOrder");
        assertThat(task.get("assignee").asText()).isEqualTo("rest-admin");
        assertThat(task.get("state").asText()).isEqualTo("ACTIVE");
        assertThat(task.get("createTime").asText()).isNotBlank();
        assertThat(task.has("endTime")).isFalse(); // open task: no endTime, never a fake one

        // The COMPLETED order ran service tasks only — an explicit empty page, not a 404.
        JsonNode completed = get("/api/instances/engine-a/" + completedId + "/tasks");
        assertThat(completed.get("tasks")).isEmpty();
        assertThat(completed.get("total").asLong()).isZero();
    }

    /* ================= Task 3: hierarchy + timeline ================= */

    @Test
    void hierarchyRendersTheCallActivityTreeFromEitherEnd() {
        // From the PARENT: the child appears with its dead-letter marker.
        JsonNode fromParent = get("/api/instances/engine-a/" + parentId + "/hierarchy");
        assertThat(fromParent.get("rootProcessInstanceId").asText()).isEqualTo(parentId);
        JsonNode root = fromParent.get("root");
        assertThat(root.get("requested").asBoolean()).isTrue();
        assertThat(root.get("hasDeadLetterJobs").asBoolean()).isFalse();
        assertThat(root.get("childTotal").asLong()).isEqualTo(1);
        assertThat(root.get("childrenTruncated").asBoolean()).isFalse();
        JsonNode child = root.get("children").get(0);
        assertThat(child.get("processInstanceId").asText()).isEqualTo(childId);
        assertThat(child.get("hasDeadLetterJobs").asBoolean()).isTrue();
        assertThat(child.get("definitionKey").asText()).isEqualTo("demoFailingPayment");

        // From the CHILD: the walk goes UP to the same root, the requested marker moves.
        JsonNode fromChild = get("/api/instances/engine-a/" + childId + "/hierarchy");
        assertThat(fromChild.get("rootProcessInstanceId").asText()).isEqualTo(parentId);
        assertThat(fromChild.get("root").get("requested").asBoolean()).isFalse();
        assertThat(fromChild.get("root").get("children").get(0).get("requested").asBoolean())
                .isTrue();
        assertThat(fromChild.get("depthLimitReached").asBoolean()).isFalse();
    }

    @Test
    void timelineListsHistoricActivitiesWithTheCallActivityChildLink() {
        JsonNode body = get("/api/instances/engine-a/" + parentId + "/timeline");

        assertThat(body.get("activities")).isNotEmpty();
        assertThat(body.get("truncated").asBoolean()).isFalse();
        JsonNode callActivity = null;
        for (JsonNode activity : body.get("activities")) {
            if ("callPayment".equals(activity.get("activityId").asText(null))) {
                callActivity = activity;
            }
        }
        assertThat(callActivity)
                .as("the call activity appears on the parent's timeline")
                .isNotNull();
        assertThat(callActivity.get("activityType").asText()).isEqualTo("callActivity");
        assertThat(callActivity.get("calledProcessInstanceId").asText())
                .as("the sub-lane link to the child instance")
                .isEqualTo(childId);
        // startTime-ascending order, proven on the wire.
        String previous = null;
        for (JsonNode activity : body.get("activities")) {
            String start = activity.get("startTime").asText();
            if (previous != null) {
                assertThat(start).isGreaterThanOrEqualTo(previous);
            }
            previous = start;
        }
    }

    /* ================= access control parity ================= */

    @Test
    void detailReadsRequireAuthenticationLikeEveryApiRoute() {
        TestRestTemplate anonymous = new TestRestTemplate();
        ResponseEntity<String> response =
                anonymous.getForEntity(rest.getRootUri() + "/api/instances/engine-a/" + childId, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> resolve = anonymous.getForEntity(rest.getRootUri() + "/api/resolve?q=x", String.class);
        assertThat(resolve.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /* ================= plumbing ================= */

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

    private static List<JsonNode> matchesOf(JsonNode body, String kind) {
        List<JsonNode> matches = new java.util.ArrayList<>();
        body.get("matches").forEach(m -> {
            if (kind.equals(m.get("kind").asText())) matches.add(m);
        });
        return matches;
    }

    private static List<String> jsonList(JsonNode array) {
        List<String> values = new java.util.ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }

    private static JsonNode variableRow(JsonNode rows, String name) {
        for (JsonNode row : rows) {
            if (name.equals(row.get("name").asText())) return row;
        }
        throw new AssertionError("variable '" + name + "' not in ledger: " + rows.toPrettyString());
    }

    /** The parked user task's id, straight from the engine (test fixture plumbing). */
    @SuppressWarnings("unchecked")
    private String engineTaskIdFor(String processInstanceId) {
        Map<String, Object> page = engine.get()
                .uri("/runtime/tasks?processInstanceId=" + processInstanceId)
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) page.get("data");
        assertThat(data).as("the user-task fixture holds one open task").isNotEmpty();
        return String.valueOf(data.get(0).get("id"));
    }
}
