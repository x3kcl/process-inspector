package io.inspector.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditEntryRepository;
import io.inspector.audit.AuditOutcome;
import io.inspector.support.EngineSeed;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Rung 4 (engine-harness): cohort migration through the bulk machinery (#496) against REAL
 * flowable-rest 6.8 ({@code engine-a}) and 7.1 ({@code engine-7}). Seeds over REST only (never an
 * {@code ACT_*} write): 103 instances on 6.8 and 100 on 7.1, all parked on {@code reviewTask} of a
 * fresh {@code demoMigration} version; the target version renames it to {@code approveTask}, so the
 * analyses carry the shared mapping document.
 *
 * <p>One arc, every acceptance item of #496 in order:
 * <ol>
 *   <li>analysis on each engine — a SUSPENDED instance is a blocker and leaves the executable set;
 *   <li>one cross-engine bulk job over both analyses (202 items) — the blocker is never sent, a
 *       protected instance is {@code skipped_protected}, an instance moved out of band since the
 *       analysis is {@code skipped} on the per-item compare-and-set;
 *   <li>engine-7's breaker is forced open mid-run — its group pauses ({@code not_run}), engine-a
 *       finishes, the job is {@code INTERRUPTED};
 *   <li>the breaker closes, "Continue as new job" (the drawer's own request) finishes engine-7.
 *       200 instances end on the target version, each with a {@code migrate-instance/v2} audit row.
 * </ol>
 *
 * <p>CI: the {@code migration-findings} leg (flowable-6 + flowable-7 + postgres). Locally:
 * {@code COMPOSE_PROFILES=flowable-6,flowable-7,postgres docker compose -f
 * docker/docker-compose.dev.yml up -d}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ENGINE_A_PASSWORD=test",
            "ENGINE_7_PASSWORD=test",
            // The bounded wait on an open breaker (issue #101) — short, so the pause is quick.
            "inspector.bulk.circuit-pause-max-ms=500",
            "inspector.bulk.circuit-pause-poll-ms=100"
        })
@ActiveProfiles("it-findings")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@AutoConfigureTestRestTemplate
class BulkMigrationIT {

    private static final String KEY = "demoMigration";
    private static final Path V1_BPMN = Path.of("..", "docker", "processes", "demo-migration-v1.bpmn20.xml");
    private static final Path V2_BPMN = Path.of("..", "docker", "processes", "demo-migration-v2.bpmn20.xml");
    private static final int CLEAN_PER_ENGINE = 100;

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
    CircuitBreakerRegistry breakers;

    @Autowired
    AuditEntryRepository audit;

    /** One engine's fixture: its pinned definitions and the instances started on {@code from}. */
    private record Leg(RestClient engine, String from, String to, List<String> started) {}

    private final Map<String, Leg> legs = new LinkedHashMap<>();

    @BeforeAll
    void seed() {
        legs.put("engine-a", leg(url("PI_ENGINE_A_PORT", "8081"), CLEAN_PER_ENGINE + 3));
        legs.put("engine-7", leg(url("PI_ENGINE_7_PORT", "8083"), CLEAN_PER_ENGINE));
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> migrationCapable("engine-a") && migrationCapable("engine-7"));
    }

    @AfterAll
    void closeBreaker() {
        breakers.circuitBreaker("engine-7").transitionToClosedState();
    }

    private static String url(String portEnv, String port) {
        return "http://localhost:" + System.getenv().getOrDefault(portEnv, port) + "/flowable-rest/service";
    }

    private Leg leg(String baseUrl, int instances) {
        RestClient engine = EngineSeed.requireReachable(baseUrl, "");
        String from = definitionId(engine, EngineSeed.deployNewVersion(engine, KEY, V1_BPMN));
        String to = definitionId(engine, EngineSeed.deployNewVersion(engine, KEY, V2_BPMN));
        List<String> started = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            started.add(start(engine, from));
        }
        return new Leg(engine, from, to, started);
    }

    @Test
    void cohortOfTwoHundredMigratesAcrossBothMajorsWithEveryRailAndResumesOnlyAsANewJob() throws Exception {
        Leg a = legs.get("engine-a");
        Leg seven = legs.get("engine-7");
        String blocked = a.started().get(0);
        String guarded = a.started().get(1);
        String moved = a.started().get(2);

        // A suspended instance cannot be previewed: the analysis counts it a blocker.
        a.engine()
                .put()
                .uri("/runtime/process-instances/" + blocked)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("action", "suspend"))
                .retrieve()
                .toBodilessEntity();
        assertThat(as("admin")
                        .postForEntity(
                                "/api/instances/engine-a/" + guarded + "/protect",
                                Map.of("reason", "BulkMigrationIT: never migrate this one"),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        JsonNode analysisA = analyze("engine-a", a);
        JsonNode analysis7 = analyze("engine-7", seven);
        assertThat(analysisA.path("blockerCount").asInt()).isEqualTo(1);
        assertThat(ids(analysisA.path("executableIds")))
                .hasSize(CLEAN_PER_ENGINE + 2)
                .doesNotContain(blocked);
        assertThat(ids(analysis7.path("executableIds"))).hasSize(CLEAN_PER_ENGINE);

        // Moved out of band AFTER the analysis: the per-item compare-and-set must catch it.
        a.engine()
                .post()
                .uri("/runtime/process-instances/" + moved + "/migrate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "toProcessDefinitionId",
                        a.to(),
                        "activityMappings",
                        List.of(Map.of("fromActivityId", "reviewTask", "toActivityId", "approveTask"))))
                .retrieve()
                .toBodilessEntity();

        ResponseEntity<String> submitted = as("admin")
                .postForEntity(
                        "/api/bulk/migrate",
                        Map.of(
                                "analysisIds",
                                List.of(
                                        analysisA.path("id").asText(),
                                        analysis7.path("id").asText()),
                                "reason",
                                "BulkMigrationIT cohort move off the bad deploy"),
                        String.class);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        String jobId = mapper.readTree(submitted.getBody()).path("id").asText();

        // Mid-run: once engine-7 has made progress, its breaker opens. Timed right after a health
        // probe (30s cycle, same breaker): a probe through the open breaker would drop the
        // migration capability and items would refuse on that instead of pausing.
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> count(job(jobId), "engine-7", "ok") >= 10 && healthAgeMs("engine-7") < 15_000);
        breakers.circuitBreaker("engine-7").transitionToForcedOpenState();

        JsonNode first = awaitTerminal(jobId);
        assertThat(first.path("verb").asText()).isEqualTo("migrate-instance");
        assertThat(first.path("state").asText()).isEqualTo("INTERRUPTED");
        assertThat(first.path("totalItems").asInt()).isEqualTo(2 * CLEAN_PER_ENGINE + 2);
        assertThat(instanceIds(first)).doesNotContain(blocked);
        assertThat(item(first, guarded).path("state").asText()).isEqualTo("skipped_protected");
        assertThat(item(first, moved).path("state").asText()).isEqualTo("skipped");
        assertThat(item(first, moved).path("detail").asText()).contains("instance-moved-since-preview");
        assertThat(count(first, "engine-a", "ok")).isEqualTo(CLEAN_PER_ENGINE);
        assertThat(count(first, "engine-7", "not_run")).isPositive();
        assertThat(count(first, "engine-7", "unknown")).isZero();
        for (JsonNode item : first.path("items")) {
            if ("engine-7".equals(item.path("engineId").asText())
                    && "failed".equals(item.path("state").asText())) {
                assertThat(item.path("detail").asText()).contains("engine-shedding-load");
            }
        }
        assertThat(definitionOf(a, blocked)).isEqualTo(a.from());
        assertThat(definitionOf(a, guarded)).isEqualTo(a.from());

        // Nothing resumes on its own: the drawer's "Continue as new job" is the only way on.
        breakers.circuitBreaker("engine-7").transitionToClosedState();
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> migrationCapable("engine-7"));
        List<Map<String, Object>> rest7 = new ArrayList<>();
        for (JsonNode item : first.path("items")) {
            String state = item.path("state").asText();
            if ("not_run".equals(state) || "failed".equals(state)) {
                rest7.add(Map.of(
                        "engineId", item.path("engineId").asText(),
                        "instanceId", item.path("instanceId").asText(),
                        "jobId", item.path("jobRef").asText()));
            }
        }
        Map<String, Object> continuation = new HashMap<>();
        continuation.put("verb", "migrate-instance");
        continuation.put("reason", "BulkMigrationIT cohort move off the bad deploy");
        continuation.put("continuedFrom", jobId);
        continuation.put("items", rest7);
        ResponseEntity<String> resumed = as("admin").postForEntity("/api/bulk", continuation, String.class);
        assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode second =
                awaitTerminal(mapper.readTree(resumed.getBody()).path("id").asText());
        assertThat(second.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(second.path("continuedFrom").asText()).isEqualTo(jobId);
        assertThat(count(second, "engine-7", "ok")).isEqualTo(rest7.size());

        int migrated = 0;
        for (Map.Entry<String, Leg> entry : legs.entrySet()) {
            Leg leg = entry.getValue();
            for (String id : leg.started()) {
                if (id.equals(blocked) || id.equals(guarded) || id.equals(moved)) {
                    continue;
                }
                assertThat(definitionOf(leg, id)).as(entry.getKey() + ":" + id).isEqualTo(leg.to());
                migrated++;
            }
        }
        assertThat(migrated).isEqualTo(2 * CLEAN_PER_ENGINE);

        // Per-item audit: migrate-instance/v2, outcome ok, never engine-validated.
        String sample = seven.started().get(seven.started().size() - 1);
        AuditEntry row =
                audit.findByEngineIdAndInstanceIdOrderByTsDesc("engine-7", sample, PageRequest.of(0, 5)).stream()
                        .filter(e -> "migrate-instance".equals(e.getAction()))
                        .findFirst()
                        .orElseThrow();
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.ok);
        JsonNode payload = mapper.readTree(row.getPayload());
        assertThat(payload.path("schema").asText()).isEqualTo("migrate-instance/v2");
        assertThat(payload.path("engineValidated").asBoolean(true)).isFalse();
        assertThat(payload.path("toProcessDefinitionId").asText()).isEqualTo(seven.to());
    }

    /* ------------------------------- helpers ------------------------------- */

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

    private long healthAgeMs(String engineId) throws Exception {
        JsonNode engines = mapper.readTree(
                as("viewer").getForEntity("/api/engines", String.class).getBody());
        for (JsonNode e : engines) {
            if (engineId.equals(e.path("id").asText()) && e.hasNonNull("lastHealthCheck")) {
                return System.currentTimeMillis()
                        - java.time.Instant.parse(e.path("lastHealthCheck").asText())
                                .toEpochMilli();
            }
        }
        return Long.MAX_VALUE;
    }

    private JsonNode analyze(String engineId, Leg leg) throws Exception {
        Map<String, Object> filter = Map.of(
                "fromDefinitionId",
                leg.from(),
                "toDefinitionId",
                leg.to(),
                "mappings",
                List.of(Map.of("fromActivityId", "reviewTask", "toActivityId", "approveTask")));
        ResponseEntity<String> started =
                as("admin").postForEntity("/api/migration/analyses?engineId=" + engineId, filter, String.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        String id = mapper.readTree(started.getBody()).path("id").asText();
        await().atMost(180, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> !"RUNNING".equals(analysis(id).path("status").asText()));
        JsonNode done = analysis(id);
        assertThat(done.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.path("lowerBound").asBoolean(true)).isFalse();
        return done;
    }

    private JsonNode analysis(String id) throws Exception {
        return mapper.readTree(as("admin")
                .getForEntity("/api/migration/analyses/" + id, String.class)
                .getBody());
    }

    private JsonNode job(String id) throws Exception {
        return mapper.readTree(
                as("admin").getForEntity("/api/bulk/" + id, String.class).getBody());
    }

    private JsonNode awaitTerminal(String id) throws Exception {
        await().atMost(180, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> {
                    String state = job(id).path("state").asText();
                    return !"PENDING".equals(state) && !"RUNNING".equals(state);
                });
        return job(id);
    }

    private static long count(JsonNode job, String engineId, String state) {
        long n = 0;
        for (JsonNode item : job.path("items")) {
            if (engineId.equals(item.path("engineId").asText())
                    && state.equals(item.path("state").asText())) {
                n++;
            }
        }
        return n;
    }

    private static JsonNode item(JsonNode job, String instanceId) {
        for (JsonNode item : job.path("items")) {
            if (instanceId.equals(item.path("instanceId").asText())) {
                return item;
            }
        }
        throw new AssertionError("no item for " + instanceId);
    }

    private static List<String> instanceIds(JsonNode job) {
        List<String> ids = new ArrayList<>();
        job.path("items").forEach(item -> ids.add(item.path("instanceId").asText()));
        return ids;
    }

    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.asText()));
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static String definitionId(RestClient engine, int version) {
        Map<String, Object> page = engine.get()
                .uri("/repository/process-definitions?key=" + KEY + "&version=" + version)
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) page.get("data");
        return String.valueOf(data.get(0).get("id"));
    }

    @SuppressWarnings("unchecked")
    private static String start(RestClient engine, String definitionId) {
        Map<String, Object> started = engine.post()
                .uri("/runtime/process-instances")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("processDefinitionId", definitionId))
                .retrieve()
                .body(Map.class);
        return String.valueOf(started.get("id"));
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
