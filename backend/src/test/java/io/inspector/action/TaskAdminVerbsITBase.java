package io.inspector.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.EngineSeed;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Rung 4 (engine-harness, #763): every task-admin verb through the BFF, asserted on the REAL
 * engine's task state read back over REST — claim / unclaim, reassign / unassign, delegate /
 * resolve, update (set and clear), add / remove identity link (user and group), complete with
 * variables. Seeds {@code demoUserTask} (FIX-PROC-02, assignee rest-admin), one instance per
 * case, plus one IDM user and one IDM group over REST; deletes all of them at the end.
 * Subclasses pick the profile and the engines: {@link TaskAdminVerbsIT} (6.8 + 7.1),
 * {@link TaskAdminVerbs68IT} (6.8 only, the nightly flowable-6 harness).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ENGINE_A_PASSWORD=test", "ENGINE_7_PASSWORD=test"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@AutoConfigureTestRestTemplate
abstract class TaskAdminVerbsITBase {

    /** A registered engine id and the flowable-rest base URL behind it. */
    record Engine(String id, String baseUrl) {
        @Override
        public String toString() {
            return id;
        }
    }

    private static final String KEY = "demoUserTask";
    private static final String REASON = "TaskAdminVerbsIT issue 763";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    private final String user = "it763u" + System.nanoTime();
    private final String group = "it763g" + System.nanoTime();
    private final Map<String, RestClient> clients = new HashMap<>();
    private final Map<String, List<String>> started = new HashMap<>();

    abstract List<Engine> engines();

    static String url(String portEnv, String port) {
        return "http://localhost:" + System.getenv().getOrDefault(portEnv, port) + "/flowable-rest/service";
    }

    @BeforeAll
    void seed() {
        for (Engine e : engines()) {
            RestClient c = EngineSeed.requireReachable(e.baseUrl(), "");
            clients.put(e.id(), c);
            started.put(e.id(), new ArrayList<>());
            EngineSeed.deployIfMissing(c, KEY, EngineSeed.USER_TASK_BPMN);
            post(c, "/identity/users", Map.of("id", user, "firstName", "It", "lastName", "763", "password", "x"));
            post(c, "/identity/groups", Map.of("id", group, "name", "it 763", "type", "assignment"));
        }
        // The IDM identity check reads the probed capabilities: wait until every engine was probed.
        await().atMost(60, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).until(this::allProbed);
    }

    private boolean allProbed() throws Exception {
        for (Engine e : engines()) {
            if (!probed(e.id())) {
                return false;
            }
        }
        return true;
    }

    @AfterAll
    void cleanup() {
        clients.forEach((id, c) -> {
            started.get(id).forEach(i -> EngineSeed.deleteInstanceQuietly(c, i));
            deleteQuietly(c, "/identity/users/" + user);
            deleteQuietly(c, "/identity/groups/" + group);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void unclaimClearsTheAssigneeAndClaimSetsIt(Engine e) throws Exception {
        String[] t = start(e);

        ok(e, t, "claim-task", Map.of());
        assertThat(task(e, t[1]).get("assignee")).isNull();

        ok(e, t, "claim-task", Map.of("assignee", user));
        assertThat(task(e, t[1]).get("assignee")).isEqualTo(user);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void reassignSetsTheAssigneeAndUnassignClearsIt(Engine e) throws Exception {
        String[] t = start(e);

        ok(e, t, "reassign-task", Map.of("assignee", user));
        assertThat(task(e, t[1]).get("assignee")).isEqualTo(user);

        ok(e, t, "unassign-task", Map.of());
        assertThat(task(e, t[1]).get("assignee")).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void delegateHandsTheTaskOverAndResolveHandsItBack(Engine e) throws Exception {
        String[] t = start(e);

        ok(e, t, "delegate-task", Map.of("assignee", user));
        Map<String, Object> delegated = task(e, t[1]);
        assertThat(delegated.get("assignee")).isEqualTo(user);
        assertThat(delegated.get("owner")).isEqualTo("rest-admin");
        assertThat(delegated.get("delegationState")).isEqualTo("pending");

        ok(e, t, "resolve-task", Map.of());
        Map<String, Object> resolved = task(e, t[1]);
        assertThat(resolved.get("delegationState")).isEqualTo("resolved");
        assertThat(resolved.get("assignee")).isEqualTo("rest-admin");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void updateSetsEachFieldThenABlankClearsOne(Engine e) throws Exception {
        String[] t = start(e);
        Map<String, Object> patch = new HashMap<>();
        patch.put("name", "Review order 763");
        patch.put("description", "set by the IT");
        patch.put("owner", user);
        patch.put("category", "it-763");
        patch.put("priority", 80);
        patch.put("dueDate", "2030-01-02T03:04:05Z");

        ok(e, t, "update-task", Map.of("task", patch));
        Map<String, Object> set = task(e, t[1]);
        assertThat(set.get("name")).isEqualTo("Review order 763");
        assertThat(set.get("description")).isEqualTo("set by the IT");
        assertThat(set.get("owner")).isEqualTo(user);
        assertThat(set.get("category")).isEqualTo("it-763");
        assertThat(set.get("priority")).isEqualTo(80);
        assertThat(String.valueOf(set.get("dueDate"))).startsWith("2030-01-02");

        ok(e, t, "update-task", Map.of("task", Map.of("description", "")));
        Map<String, Object> cleared = task(e, t[1]);
        assertThat(cleared.get("description")).isNull();
        assertThat(cleared.get("name"))
                .as("a field not in the patch is left alone")
                .isEqualTo("Review order 763");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void addAndRemoveACandidateUserAndACandidateGroup(Engine e) throws Exception {
        String[] t = start(e);

        JsonNode addedUser = ok(e, t, "add-identity-link", link("users", user));
        JsonNode addedGroup = ok(e, t, "add-identity-link", link("groups", group));

        assertThat(links(e, t[1])).contains("user:" + user + ":candidate").contains("group:" + group + ":candidate");
        // The UI message and the audit row both name what was added.
        assertThat(addedUser.path("deltaStatement").asText()).contains("'" + user + "'", "candidate", "added");
        assertThat(addedGroup.path("deltaStatement").asText()).contains("'" + group + "'", "candidate", "added");
        List<String> audited = new ArrayList<>();
        for (JsonNode row : auditRows(e.id(), t[0])) {
            if ("add-identity-link".equals(row.path("action").asText())) {
                JsonNode l = mapper.readTree(row.path("payload").asText()).path("link");
                audited.add(l.path("family").asText() + ":"
                        + l.path("identityId").asText() + ":" + l.path("type").asText());
            }
        }
        assertThat(audited).containsExactlyInAnyOrder("users:" + user + ":candidate", "groups:" + group + ":candidate");

        ok(e, t, "remove-identity-link", link("users", user));
        ok(e, t, "remove-identity-link", link("groups", group));
        assertThat(links(e, t[1]))
                .doesNotContain("user:" + user + ":candidate")
                .doesNotContain("group:" + group + ":candidate");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void completeWithVariablesEndsTheTaskAndWritesTheVariables(Engine e) throws Exception {
        String[] t = start(e);

        ok(
                e,
                t,
                "complete-task",
                Map.of(
                        "variables",
                        List.of(
                                Map.of("name", "approved", "type", "boolean", "value", true),
                                Map.of("name", "approver", "type", "string", "value", user))));

        RestClient c = clients.get(e.id());
        Integer taskStatus = c.get()
                .uri("/runtime/tasks/" + t[1])
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(taskStatus).isEqualTo(404);
        Map<String, Object> vars = new HashMap<>();
        for (Map<String, Object> v : data(c, "/history/historic-variable-instances?processInstanceId=" + t[0])) {
            @SuppressWarnings("unchecked")
            Map<String, Object> variable = (Map<String, Object>) v.get("variable");
            vars.put(String.valueOf(variable.get("name")), variable.get("value"));
        }
        assertThat(vars).containsEntry("approved", true).containsEntry("approver", user);
        assertThat(c.get()
                        .uri("/history/historic-process-instances/" + t[0])
                        .retrieve()
                        .body(Map.class)
                        .get("endTime"))
                .isNotNull();
    }

    /* ---------------------------------------------------------------------------------------- */

    private static Map<String, Object> link(String family, String identityId) {
        return Map.of("link", Map.of("family", family, "identityId", identityId, "type", "candidate"));
    }

    /** Starts an instance; returns {instanceId, taskId}. */
    private String[] start(Engine e) {
        RestClient c = clients.get(e.id());
        String instanceId = EngineSeed.startInstance(c, KEY, "it-763-" + System.nanoTime(), List.of());
        started.get(e.id()).add(instanceId);
        List<Map<String, Object>> tasks = data(c, "/runtime/tasks?processInstanceId=" + instanceId);
        assertThat(tasks).hasSize(1);
        return new String[] {instanceId, String.valueOf(tasks.get(0).get("id"))};
    }

    /** POSTs the verb for task t[1] of instance t[0] as an operator; asserts 200 + outcome ok. */
    private JsonNode ok(Engine e, String[] t, String verb, Map<String, Object> fields) throws Exception {
        Map<String, Object> body = new HashMap<>(fields);
        body.put("taskId", t[1]);
        body.put("reason", REASON);
        ResponseEntity<String> response = rest.withBasicAuth("operator", "dev")
                .postForEntity("/api/instances/" + e.id() + "/" + t[0] + "/actions/" + verb, body, String.class);
        assertThat(response.getStatusCode())
                .as(verb + " on " + e + ": " + response.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode result = mapper.readTree(response.getBody());
        assertThat(result.path("outcome").asText())
                .as(verb + " on " + e + ": " + response.getBody())
                .isEqualTo("ok");
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> task(Engine e, String taskId) {
        return clients.get(e.id())
                .get()
                .uri("/runtime/tasks/" + taskId)
                .retrieve()
                .body(Map.class);
    }

    /** The task's identity links as {@code user|group:id:type}. */
    @SuppressWarnings("unchecked")
    private List<String> links(Engine e, String taskId) {
        List<Map<String, Object>> rows = clients.get(e.id())
                .get()
                .uri("/runtime/tasks/" + taskId + "/identitylinks")
                .retrieve()
                .body(List.class);
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(
                    r.get("user") != null
                            ? "user:" + r.get("user") + ":" + r.get("type")
                            : "group:" + r.get("group") + ":" + r.get("type"));
        }
        return out;
    }

    private JsonNode auditRows(String engineId, String instanceId) throws Exception {
        ResponseEntity<String> audit = rest.withBasicAuth("operator", "dev")
                .getForEntity("/api/instances/" + engineId + "/" + instanceId + "/audit", String.class);
        assertThat(audit.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(audit.getBody());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> data(RestClient c, String uri) {
        Map<String, Object> page = c.get().uri(uri).retrieve().body(Map.class);
        return (List<Map<String, Object>>) page.get("data");
    }

    private boolean probed(String engineId) throws Exception {
        for (JsonNode e : mapper.readTree(rest.withBasicAuth("viewer", "dev")
                .getForEntity("/api/engines", String.class)
                .getBody())) {
            if (engineId.equals(e.path("id").asText())) {
                return e.path("engineVersion").isTextual();
            }
        }
        return false;
    }

    private static void post(RestClient c, String uri, Map<String, Object> body) {
        c.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private static void deleteQuietly(RestClient c, String uri) {
        try {
            c.delete().uri(uri).retrieve().toBodilessEntity();
        } catch (RuntimeException ignored) {
            // already gone — residue cleanup must never fail a test
        }
    }
}
