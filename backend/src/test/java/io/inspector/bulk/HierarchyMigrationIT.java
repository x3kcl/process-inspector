package io.inspector.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditEntryRepository;
import io.inspector.audit.AuditOutcome;
import io.inspector.support.EngineSeed;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Rung 4 (engine-harness): hierarchy-aware migration (#497) against REAL flowable-rest 6.8
 * ({@code engine-a}) and 7.1 ({@code engine-7}). Seeds over REST only (never an {@code ACT_*}
 * write). The fixture is a running 3-level call-activity tree, {@code demoHierarchyRoot} →
 * {@code demoHierarchyA} → {@code demoHierarchyB}, started on version 1 of each definition;
 * version 2 of each is deployed afterwards, and B's version 2 renames its user task so the leaf
 * needs a mapping.
 *
 * <ul>
 *   <li>one plan migrates all three levels, children first: the order is proven by the per-item
 *       audit timestamps, and the envelope and every item share one audit correlation id;
 *   <li>a forced failure on B leaves A and Root {@code not_run} with the named cause and on their
 *       old version; a continuation of the parent alone is refused; once B is fixed, "Continue as
 *       new job" finishes the tree in the same order;
 *   <li>a prod engine needs the typed count of the executable set;
 *   <li>a multi-instance call activity wider than one child read is reported with its exact total
 *       and the plan is refused, not partly executed.
 * </ul>
 *
 * <p>CI: the {@code migration-findings} leg (flowable-6 + flowable-7 + postgres). Locally:
 * {@code COMPOSE_PROFILES=flowable-6,flowable-7,postgres docker compose -f
 * docker/docker-compose.dev.yml up -d}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ENGINE_A_PASSWORD=test", "ENGINE_7_PASSWORD=test"})
@ActiveProfiles("it-findings")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@AutoConfigureTestRestTemplate
class HierarchyMigrationIT {

    private static final String ROOT = "demoHierarchyRoot";
    private static final String A = "demoHierarchyA";
    private static final String B = "demoHierarchyB";
    private static final String REASON = "HierarchyMigrationIT move the tree off the bad deploy";

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

    @Autowired
    AuditEntryRepository audit;

    /** One running tree: the root and its call-activity descendants. */
    private record Tree(String root, String a, String b) {}

    /** One engine's fixture: version 1 ids the trees run on, version 2 ids to migrate to. */
    private record Leg(RestClient engine, Map<String, String> from, Map<String, String> to, List<Tree> trees) {}

    private final Map<String, Leg> legs = new LinkedHashMap<>();
    private final List<Runnable> cleanup = new ArrayList<>();

    @BeforeAll
    void seed() {
        legs.put("engine-a", leg(url("PI_ENGINE_A_PORT", "8081"), 3));
        legs.put("engine-7", leg(url("PI_ENGINE_7_PORT", "8083"), 2));
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> migrationCapable("engine-a") && migrationCapable("engine-7"));
    }

    @AfterAll
    void deleteWhatThisSuiteStarted() {
        cleanup.forEach(Runnable::run);
    }

    private static String url(String portEnv, String port) {
        return "http://localhost:" + System.getenv().getOrDefault(portEnv, port) + "/flowable-rest/service";
    }

    /**
     * Version 1 leaf-first (a call activity binds the latest version of its called key when the
     * child starts), then the trees, then version 2 — so every tree runs on version 1.
     */
    private Leg leg(String baseUrl, int trees) {
        RestClient engine = EngineSeed.requireReachable(baseUrl, "");
        Map<String, String> from = new HashMap<>();
        for (String key : List.of(B, A, ROOT)) {
            from.put(key, definitionId(engine, key, EngineSeed.deployNewVersion(engine, key, bpmn(key, 1))));
        }
        List<Tree> started = new ArrayList<>();
        for (int i = 0; i < trees; i++) {
            String root = EngineSeed.startInstance(engine, ROOT, "IT497-" + UUID.randomUUID(), List.of());
            cleanup.add(() -> EngineSeed.deleteInstanceQuietly(engine, root));
            String a = EngineSeed.childInstanceOf(engine, root);
            started.add(new Tree(root, a, EngineSeed.childInstanceOf(engine, a)));
        }
        Map<String, String> to = new HashMap<>();
        for (String key : List.of(B, A, ROOT)) {
            to.put(key, definitionId(engine, key, EngineSeed.deployNewVersion(engine, key, bpmn(key, 2))));
        }
        return new Leg(engine, from, to, started);
    }

    private static Path bpmn(String key, int version) {
        String name = key.equals(ROOT) ? "root" : key.equals(A) ? "a" : "b";
        return Path.of("..", "docker", "processes", "demo-hierarchy-" + name + "-v" + version + ".bpmn20.xml");
    }

    /* ------------------------------- the acceptance arcs ------------------------------- */

    @ParameterizedTest
    @ValueSource(strings = {"engine-a", "engine-7"})
    void threeLevelsMigrateInOnePlanChildrenFirstWithOneCorrelationId(String engineId) throws Exception {
        Leg leg = legs.get(engineId);
        Tree tree = leg.trees().get(0);
        assertThat(definitionOf(leg, tree.b())).isEqualTo(leg.from().get(B));

        JsonNode plan = createPlan(engineId, tree.root());
        assertThat(plan.path("executable").asBoolean()).isFalse();
        assertThat(plan.path("truncations")).isEmpty();
        assertThat(plan.path("circularReferences")).isEmpty();
        assertThat(nodeDepths(plan))
                .containsEntry(tree.root(), 0)
                .containsEntry(tree.a(), 1)
                .containsEntry(tree.b(), 2)
                .hasSize(3);
        assertThat(groupStatuses(plan))
                .containsEntry(leg.from().get(ROOT), "PENDING")
                .containsEntry(leg.from().get(A), "PENDING")
                .containsEntry(leg.from().get(B), "PENDING");

        JsonNode ready = configureAll(plan.path("id").asText(), leg);
        assertThat(ready.path("executableCount").asInt()).isEqualTo(3);
        assertThat(ready.path("notSentCount").asInt()).isZero();

        JsonNode done = awaitTerminal(submit(ready.path("id").asText(), null));

        assertThat(done.path("verb").asText()).isEqualTo("migrate-instance");
        assertThat(done.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(done.path("totalItems").asInt()).isEqualTo(3);
        for (String id : List.of(tree.root(), tree.a(), tree.b())) {
            assertThat(item(done, id).path("state").asText()).as(id).isEqualTo("ok");
        }
        assertThat(item(done, tree.b()).path("level").asInt()).isEqualTo(2);
        assertThat(item(done, tree.b()).path("parentInstanceId").asText()).isEqualTo(tree.a());
        assertThat(item(done, tree.a()).path("parentInstanceId").asText()).isEqualTo(tree.root());
        assertThat(definitionOf(leg, tree.b())).isEqualTo(leg.to().get(B));
        assertThat(definitionOf(leg, tree.a())).isEqualTo(leg.to().get(A));
        assertThat(definitionOf(leg, tree.root())).isEqualTo(leg.to().get(ROOT));

        assertChildrenFirstByAudit(engineId, done, tree);
    }

    @ParameterizedTest
    @ValueSource(strings = {"engine-a", "engine-7"})
    void aFailedLeafLeavesItsAncestorsNotRunAndTheContinuationKeepsTheOrder(String engineId) throws Exception {
        Leg leg = legs.get(engineId);
        Tree tree = leg.trees().get(1);
        JsonNode ready =
                configureAll(createPlan(engineId, tree.root()).path("id").asText(), leg);

        // The forced failure: B is suspended AFTER the plan was approved, so its migrate refuses.
        EngineSeed.suspend(leg.engine(), tree.b());

        JsonNode first = awaitTerminal(submit(ready.path("id").asText(), null));

        assertThat(first.path("state").asText()).isEqualTo("COMPLETED");
        JsonNode b = item(first, tree.b());
        JsonNode a = item(first, tree.a());
        JsonNode root = item(first, tree.root());
        assertThat(b.path("state").asText()).isEqualTo("failed");
        assertThat(b.path("detail").asText()).contains("instance-suspended");
        assertThat(a.path("state").asText()).isEqualTo("not_run");
        assertThat(a.path("detail").asText())
                .contains("blocked by child " + engineId + ":" + tree.b())
                .contains("failed")
                .contains("instance-suspended");
        assertThat(root.path("state").asText()).isEqualTo("not_run");
        assertThat(root.path("detail").asText())
                .contains("blocked by child " + engineId + ":" + tree.a())
                .contains("not_run");
        // Nothing moved, and the parents were never attempted: no migrate audit row exists for them.
        assertThat(definitionOf(leg, tree.b())).isEqualTo(leg.from().get(B));
        assertThat(definitionOf(leg, tree.a())).isEqualTo(leg.from().get(A));
        assertThat(definitionOf(leg, tree.root())).isEqualTo(leg.from().get(ROOT));
        assertThat(migrateRows(engineId, tree.a())).isEmpty();
        assertThat(migrateRows(engineId, tree.root())).isEmpty();

        // The parent alone cannot be continued over a child that did not move.
        ResponseEntity<String> parentOnly = continueJob(first, List.of(tree.a()));
        assertThat(parentOnly.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parentOnly.getBody()).contains("hierarchy-child-unresolved");
        assertThat(definitionOf(leg, tree.a())).isEqualTo(leg.from().get(A));

        // Fix the cause, then the drawer's "Continue as new job" over the not-run/failed items.
        leg.engine()
                .put()
                .uri("/runtime/process-instances/" + tree.b())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("action", "activate"))
                .retrieve()
                .toBodilessEntity();
        ResponseEntity<String> resumed = continueJob(first, List.of(tree.root(), tree.a(), tree.b()));
        assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode second =
                awaitTerminal(mapper.readTree(resumed.getBody()).path("id").asText());

        assertThat(second.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(second.path("continuedFrom").asText())
                .isEqualTo(first.path("id").asText());
        for (String id : List.of(tree.root(), tree.a(), tree.b())) {
            assertThat(item(second, id).path("state").asText()).as(id).isEqualTo("ok");
        }
        assertThat(definitionOf(leg, tree.root())).isEqualTo(leg.to().get(ROOT));
        assertChildrenFirstByAudit(engineId, second, tree);
    }

    @Test
    void aProdEngineNeedsTheTypedCountOfTheExecutableSet() throws Exception {
        // engine-a-prod is the same 6.8 engine registered as prod.
        Leg leg = legs.get("engine-a");
        Tree tree = leg.trees().get(2);
        JsonNode ready =
                configureAll(createPlan("engine-a-prod", tree.root()).path("id").asText(), leg);
        String planId = ready.path("id").asText();

        ResponseEntity<String> untyped = post("/api/bulk/migrate-hierarchy", submitBody(planId, null));
        assertThat(untyped.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(untyped.getBody()).contains("bulk-migrate-confirm-count-required");
        ResponseEntity<String> wrong = post("/api/bulk/migrate-hierarchy", submitBody(planId, 2));
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(wrong.getBody()).contains("bulk-count-drift");
        assertThat(definitionOf(leg, tree.b())).isEqualTo(leg.from().get(B));

        JsonNode done = awaitTerminal(submit(planId, 3));
        assertThat(done.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(definitionOf(leg, tree.root())).isEqualTo(leg.to().get(ROOT));
    }

    @Test
    void aMultiInstanceLevelWiderThanOneChildReadIsReportedAndThePlanRefused() throws Exception {
        Leg leg = legs.get("engine-a");
        RestClient engine = leg.engine();
        EngineSeed.deployIfMissing(
                engine, "demoWideChild", Path.of("..", "docker", "processes", "demo-wide-child.bpmn20.xml"));
        EngineSeed.deployIfMissing(
                engine, "demoWideParent", Path.of("..", "docker", "processes", "demo-wide-parent.bpmn20.xml"));
        // 60 parallel call-activity children; this profile reads 10 children per node.
        String wide = EngineSeed.startInstance(engine, "demoWideParent", "IT497-wide-" + UUID.randomUUID(), List.of());
        cleanup.add(() -> EngineSeed.deleteInstanceQuietly(engine, wide));

        JsonNode plan = createPlan("engine-a", wide);

        assertThat(plan.path("executable").asBoolean()).isFalse();
        assertThat(plan.path("notExecutableReason").asText()).contains("60 running children");
        JsonNode truncation = plan.path("truncations").get(0);
        assertThat(truncation.path("kind").asText()).isEqualTo("breadth");
        assertThat(truncation.path("instanceId").asText()).isEqualTo(wide);
        assertThat(truncation.path("total").asLong()).isEqualTo(60);
        assertThat(truncation.path("enumerated").asInt())
                .isEqualTo(plan.path("breadthCap").asInt())
                .isLessThan(60);
        assertThat(plan.path("nodes")).hasSize(plan.path("breadthCap").asInt() + 1);

        ResponseEntity<String> refused =
                post("/api/bulk/migrate-hierarchy", submitBody(plan.path("id").asText(), null));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("hierarchy-plan-truncated");
    }

    /* ------------------------------- helpers ------------------------------- */

    /** Audit timestamps prove the order: B's row before A's before Root's, each child settled first. */
    private void assertChildrenFirstByAudit(String engineId, JsonNode job, Tree tree) throws Exception {
        AuditEntry b = migrateRow(job, tree.b());
        AuditEntry a = migrateRow(job, tree.a());
        AuditEntry root = migrateRow(job, tree.root());
        assertThat(b.getTs()).isBefore(a.getTs());
        assertThat(a.getTs()).isBefore(root.getTs());
        assertThat(finishedAt(job, tree.b())).isBeforeOrEqualTo(a.getTs());
        assertThat(finishedAt(job, tree.a())).isBeforeOrEqualTo(root.getTs());
        for (AuditEntry row : List.of(b, a, root)) {
            assertThat(row.getAction()).isEqualTo("migrate-instance");
            assertThat(row.getOutcome()).isEqualTo(AuditOutcome.ok);
            assertThat(row.getEngineId()).isEqualTo(engineId);
            assertThat(mapper.readTree(row.getPayload()).path("schema").asText())
                    .isEqualTo("migrate-instance/v2");
        }
        // One correlation id for the whole plan: the envelope and every item row.
        String jobId = job.path("id").asText();
        AuditEntry envelope = audit.findAll().stream()
                .filter(row -> "bulk:migrate-instance".equals(row.getAction())
                        && row.getPayload().contains(jobId))
                .findFirst()
                .orElseThrow();
        assertThat(List.of(b.getCorrelationId(), a.getCorrelationId(), root.getCorrelationId()))
                .containsOnly(envelope.getCorrelationId());
        assertThat(envelope.getTs()).isBefore(b.getTs());
    }

    private AuditEntry migrateRow(JsonNode job, String instanceId) {
        return audit.findById(
                        UUID.fromString(item(job, instanceId).path("auditId").asText()))
                .orElseThrow();
    }

    private List<AuditEntry> migrateRows(String engineId, String instanceId) {
        return audit.findByEngineIdAndInstanceIdOrderByTsDesc(engineId, instanceId, PageRequest.of(0, 10)).stream()
                .filter(row -> "migrate-instance".equals(row.getAction()))
                .toList();
    }

    private static Instant finishedAt(JsonNode job, String instanceId) {
        return Instant.parse(item(job, instanceId).path("finishedAt").asText());
    }

    private JsonNode createPlan(String engineId, String rootInstanceId) throws Exception {
        ResponseEntity<String> created =
                post("/api/migration/hierarchy/plans?engineId=" + engineId, Map.of("rootInstanceId", rootInstanceId));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(created.getBody());
    }

    /** Configures every definition of the plan to its version 2 and waits for the estimates. */
    private JsonNode configureAll(String planId, Leg leg) throws Exception {
        for (String key : List.of(ROOT, A, B)) {
            Map<String, Object> decision = new HashMap<>();
            decision.put("definitionId", leg.from().get(key));
            decision.put("toDefinitionId", leg.to().get(key));
            if (key.equals(B)) {
                decision.put("mappings", List.of(Map.of("fromActivityId", "reviewB", "toActivityId", "approveB")));
            }
            ResponseEntity<String> decided = as("admin")
                    .exchange(
                            "/api/migration/hierarchy/plans/" + planId + "/groups",
                            HttpMethod.PUT,
                            new HttpEntity<>(decision),
                            String.class);
            assertThat(decided.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        await().atMost(120, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> plan(planId).path("executable").asBoolean());
        JsonNode ready = plan(planId);
        assertThat(groupStatuses(ready).values()).containsOnly("CONFIGURED");
        return ready;
    }

    private JsonNode plan(String id) throws Exception {
        return mapper.readTree(as("admin")
                .getForEntity("/api/migration/hierarchy/plans/" + id, String.class)
                .getBody());
    }

    private static Map<String, Object> submitBody(String planId, Integer confirmedCount) {
        Map<String, Object> body = new HashMap<>();
        body.put("planId", planId);
        body.put("reason", REASON);
        if (confirmedCount != null) {
            body.put("confirmedCount", confirmedCount);
        }
        return body;
    }

    private String submit(String planId, Integer confirmedCount) throws Exception {
        ResponseEntity<String> submitted = post("/api/bulk/migrate-hierarchy", submitBody(planId, confirmedCount));
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(submitted.getBody()).path("id").asText();
    }

    /** The drawer's own "Continue as new job" request over the named items of a finished job. */
    private ResponseEntity<String> continueJob(JsonNode job, List<String> instanceIds) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String id : instanceIds) {
            JsonNode item = item(job, id);
            items.add(Map.of(
                    "engineId", item.path("engineId").asText(),
                    "instanceId", id,
                    "jobId", item.path("jobRef").asText()));
        }
        Map<String, Object> continuation = new HashMap<>();
        continuation.put("verb", "migrate-instance");
        continuation.put("reason", REASON);
        continuation.put("continuedFrom", job.path("id").asText());
        continuation.put("items", items);
        return post("/api/bulk", continuation);
    }

    private ResponseEntity<String> post(String path, Object body) {
        return as("admin").postForEntity(path, body, String.class);
    }

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    private boolean migrationCapable(String engineId) throws Exception {
        JsonNode engines = mapper.readTree(
                as("viewer").getForEntity("/api/engines", String.class).getBody());
        for (JsonNode e : engines) {
            if (engineId.equals(e.path("id").asText())) {
                return e.path("capabilities").path("migration").asBoolean(false);
            }
        }
        return false;
    }

    private JsonNode job(String id) throws Exception {
        return mapper.readTree(
                as("admin").getForEntity("/api/bulk/" + id, String.class).getBody());
    }

    private JsonNode awaitTerminal(String id) throws Exception {
        await().atMost(120, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> {
                    String state = job(id).path("state").asText();
                    return !"PENDING".equals(state) && !"RUNNING".equals(state);
                });
        return job(id);
    }

    private static JsonNode item(JsonNode job, String instanceId) {
        for (JsonNode item : job.path("items")) {
            if (instanceId.equals(item.path("instanceId").asText())) {
                return item;
            }
        }
        throw new AssertionError("no item for " + instanceId);
    }

    private static Map<String, Integer> nodeDepths(JsonNode plan) {
        Map<String, Integer> depths = new HashMap<>();
        plan.path("nodes")
                .forEach(node -> depths.put(
                        node.path("instanceId").asText(), node.path("depth").asInt()));
        return depths;
    }

    private static Map<String, String> groupStatuses(JsonNode plan) {
        Map<String, String> statuses = new HashMap<>();
        plan.path("groups")
                .forEach(group -> statuses.put(
                        group.path("definitionId").asText(),
                        group.path("status").asText()));
        return statuses;
    }

    @SuppressWarnings("unchecked")
    private static String definitionId(RestClient engine, String key, int version) {
        Map<String, Object> page = engine.get()
                .uri("/repository/process-definitions?key=" + key + "&version=" + version)
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) page.get("data");
        return String.valueOf(data.get(0).get("id"));
    }

    @SuppressWarnings("unchecked")
    private static String definitionOf(Leg leg, String instanceId) {
        Map<String, Object> instance = leg.engine()
                .get()
                .uri("/runtime/process-instances/" + instanceId)
                .retrieve()
                .body(Map.class);
        return String.valueOf(instance.get("processDefinitionId"));
    }
}
