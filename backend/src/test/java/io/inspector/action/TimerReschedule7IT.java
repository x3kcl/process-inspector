package io.inspector.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.EngineSeed;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Rung 4 (engine-harness, #732): the reschedule-timer verb against a REAL Flowable 7.1 engine
 * ({@code engine-7}) and its refusal on a real 6.8 engine ({@code engine-a}), with a real Postgres.
 * Seeds {@code demoTimerWait} (FIX-PROC-03, timer from the {@code dueDuration} variable) over REST
 * with a one-year timer; deletes the instances it started.
 *
 * <p>CI: its own {@code migration-findings}-stack leg (flowable-6 + flowable-7 + postgres). Locally:
 * {@code COMPOSE_PROFILES=flowable-6,flowable-7,postgres docker compose -f
 * docker/docker-compose.dev.yml up -d}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ENGINE_A_PASSWORD=test", "ENGINE_7_PASSWORD=test"})
@ActiveProfiles("it-findings")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@AutoConfigureTestRestTemplate
class TimerReschedule7IT {

    private static final String KEY = "demoTimerWait";
    private static final Path BPMN = Path.of("..", "docker", "processes", "demo-timer-wait.bpmn20.xml");
    private static final String FAR = "P365D";

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

    private RestClient six;
    private RestClient seven;
    private final List<String> started6 = new ArrayList<>();
    private final List<String> started7 = new ArrayList<>();

    @BeforeAll
    void engines() {
        six = EngineSeed.requireReachable(url("PI_ENGINE_A_PORT", "8081"), "");
        seven = EngineSeed.requireReachable(url("PI_ENGINE_7_PORT", "8083"), "--profile flowable-7");
        EngineSeed.deployIfMissing(six, KEY, BPMN);
        EngineSeed.deployIfMissing(seven, KEY, BPMN);
        // The capability gate reads the probed health: wait until both engines have been probed.
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> probed("engine-a") && probed("engine-7") && rescheduleCapable("engine-7"));
    }

    @AfterAll
    void cleanup() {
        started6.forEach(id -> EngineSeed.deleteInstanceQuietly(six, id));
        started7.forEach(id -> EngineSeed.deleteInstanceQuietly(seven, id));
    }

    @Test
    void rescheduleOn71MovesTheDueDateFiresTheTimerAndAuditsOldAndNew() throws Exception {
        String instanceId = start(seven, started7);
        Map<String, Object> timer = timerJob(seven, instanceId);
        String jobId = String.valueOf(timer.get("id"));
        Instant oldDue = instant(timer.get("dueDate"));
        Instant newDue = Instant.now().plusSeconds(15).truncatedTo(ChronoUnit.SECONDS);

        ResponseEntity<String> response = reschedule("engine-7", instanceId, jobId, newDue.toString());

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(response.getBody()).path("outcome").asText()).isEqualTo("ok");
        // #744: the message names the replacement job, not the dead one.
        assertThat(mapper.readTree(response.getBody()).path("deltaStatement").asText())
                .contains(String.valueOf(timerJob(seven, instanceId).get("id")));

        // The instance's timer job now carries the requested instant (15s out: not fired yet).
        // Flowable 7.1 reschedules by replacing the job, so the job id is a new one.
        Map<String, Object> moved = timerJob(seven, instanceId);
        assertThat(String.valueOf(moved.get("id"))).isNotEqualTo(jobId);
        assertThat(instant(moved.get("dueDate"))).isEqualTo(newDue);
        assertThat(timerJobById(seven, jobId)).isFalse();
        assertThat(mapper.readTree(response.getBody()).path("newJobId").asText())
                .isEqualTo(String.valueOf(moved.get("id")));

        // …then the instance moves past the timer and ends (timer acquisition runs every ~10s).
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(endTime(seven, instanceId)).isNotNull());

        JsonNode row = auditRows("engine-7", instanceId).get(0);
        assertThat(row.path("action").asText()).isEqualTo("reschedule-timer");
        assertThat(row.path("outcome").asText()).isEqualTo("ok");
        JsonNode payload = mapper.readTree(row.path("payload").asText());
        assertThat(payload.path("jobId").asText()).isEqualTo(jobId);
        assertThat(instant(payload.path("dueDate").asText())).isEqualTo(oldDue);
        assertThat(payload.path("newDueDate").asText()).isEqualTo(newDue.toString());
    }

    @Test
    void rescheduleOn68IsRefusedByTheCapabilityGateAndNothingIsSent() throws Exception {
        String instanceId = start(six, started6);
        Map<String, Object> timer = timerJob(six, instanceId);
        String jobId = String.valueOf(timer.get("id"));
        Object dueBefore = timer.get("dueDate");

        ResponseEntity<String> refused = reschedule(
                "engine-a",
                instanceId,
                jobId,
                Instant.now().plus(1, ChronoUnit.HOURS).toString());

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode problem = mapper.readTree(refused.getBody());
        assertThat(problem.path("code").asText()).isEqualTo("timer-reschedule-unsupported");
        assertThat(problem.toString()).contains("needs Flowable 7.x");
        // Nothing sent: the same timer job still waits on its original due date, nothing audited.
        Map<String, Object> after = timerJob(six, instanceId);
        assertThat(after.get("id")).isEqualTo(timer.get("id"));
        assertThat(after.get("dueDate")).isEqualTo(dueBefore);
        assertThat(auditRows("engine-a", instanceId)).isEmpty();
    }

    /* ---------------------------------------------------------------------------------------- */

    private String start(RestClient engine, List<String> started) {
        String id = EngineSeed.startInstance(
                engine,
                KEY,
                "it-reschedule-" + System.nanoTime(),
                List.of(Map.of("name", "dueDuration", "type", "string", "value", FAR)));
        started.add(id);
        return id;
    }

    private ResponseEntity<String> reschedule(String engineId, String instanceId, String jobId, String dueDate) {
        return as("operator")
                .postForEntity(
                        "/api/instances/" + engineId + "/" + instanceId + "/actions/reschedule-timer",
                        Map.of("jobId", jobId, "dueDate", dueDate, "reason", "TimerReschedule7IT issue 732"),
                        String.class);
    }

    private JsonNode auditRows(String engineId, String instanceId) throws Exception {
        ResponseEntity<String> audit =
                as("operator").getForEntity("/api/instances/" + engineId + "/" + instanceId + "/audit", String.class);
        assertThat(audit.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(audit.getBody());
    }

    private Map<String, Object> timerJob(RestClient engine, String instanceId) {
        Map<String, Object> job = timerJobOrNull(engine, instanceId);
        assertThat(job).as("timer job of " + instanceId).isNotNull();
        return job;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> timerJobOrNull(RestClient engine, String instanceId) {
        Map<String, Object> page = engine.get()
                .uri("/management/timer-jobs?processInstanceId=" + instanceId)
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) page.get("data");
        return data == null || data.isEmpty() ? null : data.get(0);
    }

    private static boolean timerJobById(RestClient engine, String jobId) {
        return engine.get()
                .uri("/management/timer-jobs/" + jobId)
                .exchange((req, res) -> res.getStatusCode().is2xxSuccessful());
    }

    @SuppressWarnings("unchecked")
    private static Object endTime(RestClient engine, String instanceId) {
        Map<String, Object> hpi = engine.get()
                .uri("/history/historic-process-instances/" + instanceId)
                .retrieve()
                .body(Map.class);
        return hpi.get("endTime");
    }

    private boolean probed(String engineId) throws Exception {
        return engine(engineId).path("engineVersion").isTextual();
    }

    private boolean rescheduleCapable(String engineId) throws Exception {
        return engine(engineId).path("capabilities").path("timerReschedule").asBoolean(false);
    }

    private JsonNode engine(String engineId) throws Exception {
        for (JsonNode e : mapper.readTree(
                as("viewer").getForEntity("/api/engines", String.class).getBody())) {
            if (engineId.equals(e.path("id").asText())) {
                return e;
            }
        }
        return mapper.createObjectNode();
    }

    private static Instant instant(Object iso) {
        return OffsetDateTime.parse(String.valueOf(iso)).toInstant();
    }

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    private static String url(String portEnv, String port) {
        return "http://localhost:" + System.getenv().getOrDefault(portEnv, port) + "/flowable-rest/service";
    }
}
