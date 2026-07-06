package io.inspector.action;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Rung 4 (engine-harness): the M4 verb rails against REAL flowable-rest 6.8 AND a REAL
 * Postgres 16 (Testcontainers) — Flyway's V1__init.sql applied, Hibernate validating
 * against it, the audit golden master written through the actual JDBC path. Fixtures are
 * seeded strictly over REST; every wait is Awaitility against real engine/BFF state.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml up -d   (flowable-6 profile)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CorrectiveActionIT {

    private static final String ENGINE = "http://localhost:8081/flowable-rest/service";

    // Testcontainers Postgres shared by this class's context; Ryuk reaps it afterwards.
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

    private RestClient engine;
    private final String businessKey = "it-actions-" + UUID.randomUUID();

    @BeforeAll
    void seedOrganically() {
        engine = EngineSeed.requireReachable(ENGINE, "");
        EngineSeed.deployIfMissing(engine, "demoFailingPayment", EngineSeed.FAILING_PAYMENT_BPMN);
        EngineSeed.deployIfMissing(engine, "demoUserTask", EngineSeed.USER_TASK_BPMN);
    }

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    /* ------------------------------------------------------------------------------
     * M4 "done when" arc: a dead-lettered demoFailingPayment job is retried by a
     * RESPONDER, moves back to the executable queue, and the action lands in Postgres.
     * ------------------------------------------------------------------------------ */
    @Test
    void tierZeroRetryMovesTheDeadLetterJobBackAndIsAuditedInPostgres() throws Exception {
        String instanceId = EngineSeed.startFailing(engine, "demoFailingPayment", businessKey + "-retry");
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> EngineSeed.deadLetterCountFor(engine, instanceId) == 1);
        String jobId = EngineSeed.deadLetterJobIdFor(engine, instanceId);

        ResponseEntity<String> response = as("responder")
                .postForEntity(
                        "/api/instances/engine-a/" + instanceId + "/actions/retry-job",
                        Map.of("jobId", jobId),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode result = mapper.readTree(response.getBody());
        assertThat(result.path("outcome").asText()).isEqualTo("ok");
        assertThat(result.path("deltaStatement").asText()).contains("executable queue");
        assertThat(result.path("auditId").asText()).isNotBlank();

        // The move is synchronous: the DLQ no longer holds the job (a re-dead-letter
        // needs 3 fresh failures × asyncFailedJobWaitTime — far outside this check).
        assertThat(EngineSeed.deadLetterCountFor(engine, instanceId)).isZero();

        // …and the audit golden master has the row, readable through the BFF (actor,
        // outcome, payload — proving engine → service → Postgres → API end to end).
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            ResponseEntity<String> audit =
                    as("operator").getForEntity("/api/instances/engine-a/" + instanceId + "/audit", String.class);
            assertThat(audit.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode rows = mapper.readTree(audit.getBody());
            assertThat(rows).isNotEmpty();
            JsonNode row = rows.get(0);
            assertThat(row.path("action").asText()).isEqualTo("retry-job");
            assertThat(row.path("actor").asText()).isEqualTo("responder");
            assertThat(row.path("outcome").asText()).isEqualTo("ok");
            assertThat(row.path("payload").asText()).contains(jobId);
        });
    }

    /* ------------------------------------------------------------------------------
     * R-SEM-09: edit-variable is compare-and-set — a stale expectedOldValue is refused
     * with 409 + the fresh value, the engine stays untouched, and the refused attempt
     * is audited `failed` for the next shift.
     * ------------------------------------------------------------------------------ */
    @Test
    void editVariableFailsCleanlyOnCasConflictAndSucceedsWithTheFreshValue() throws Exception {
        String instanceId = EngineSeed.startInstance(
                engine,
                "demoUserTask",
                businessKey + "-cas",
                List.of(Map.of("name", "amount", "type", "integer", "value", 100)));

        Map<String, Object> staleEdit =
                Map.of("variable", Map.of("name", "amount", "type", "integer", "value", 200, "expectedOldValue", 999));
        ResponseEntity<String> conflict = as("operator")
                .postForEntity(
                        "/api/instances/engine-a/" + instanceId + "/actions/edit-variable", staleEdit, String.class);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = mapper.readTree(conflict.getBody());
        assertThat(problem.path("code").asText()).isEqualTo("cas-conflict");
        assertThat(problem.path("currentValue").asInt()).isEqualTo(100);

        // engine untouched — the value is still 100
        Map<String, Object> variable = engine.get()
                .uri("/runtime/process-instances/{id}/variables/amount", instanceId)
                .retrieve()
                .body(Map.class);
        assertThat(((Number) variable.get("value")).intValue()).isEqualTo(100);

        // the refused attempt is on record
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            JsonNode rows = mapper.readTree(as("operator")
                    .getForEntity("/api/instances/engine-a/" + instanceId + "/audit", String.class)
                    .getBody());
            assertThat(rows).isNotEmpty();
            assertThat(rows.get(0).path("outcome").asText()).isEqualTo("failed");
            assertThat(rows.get(0).path("httpStatus").asInt()).isEqualTo(409);
        });

        // with the FRESH expected value the edit applies (typed: integer stays integer)
        Map<String, Object> freshEdit =
                Map.of("variable", Map.of("name", "amount", "type", "integer", "value", 200, "expectedOldValue", 100));
        ResponseEntity<String> applied = as("operator")
                .postForEntity(
                        "/api/instances/engine-a/" + instanceId + "/actions/edit-variable", freshEdit, String.class);
        assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> after = engine.get()
                .uri("/runtime/process-instances/{id}/variables/amount", instanceId)
                .retrieve()
                .body(Map.class);
        assertThat(((Number) after.get("value")).intValue()).isEqualTo(200);
        assertThat(after.get("type")).isEqualTo("integer");
    }

    /* ------------------------------------------------------------------------------
     * SPEC §6 tier 3 on prod: the typed token is the SERVER-FRESH business key —
     * wrong token = refused with nothing happened; right token = irreversible delete.
     * ------------------------------------------------------------------------------ */
    @Test
    void tierThreeTerminateOnProdTwinDemandsTheBusinessKeyToken() throws Exception {
        String key = businessKey + "-terminate";
        String instanceId = EngineSeed.startInstance(engine, "demoUserTask", key, List.of());

        ResponseEntity<String> refused = as("admin")
                .postForEntity(
                        "/api/instances/engine-a-prod/" + instanceId + "/actions/terminate-delete",
                        Map.of("reason", "IT teardown of a finished incident case"),
                        String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(refused.getBody()).path("code").asText()).isEqualTo("confirm-token-mismatch");
        assertThat(EngineSeed.deadLetterCountFor(engine, instanceId)).isZero(); // instance untouched…
        Map<String, Object> alive = engine.get()
                .uri("/runtime/process-instances/{id}", instanceId)
                .retrieve()
                .body(Map.class);
        assertThat(alive).isNotNull(); // …and still running

        ResponseEntity<String> done = as("admin")
                .postForEntity(
                        "/api/instances/engine-a-prod/" + instanceId + "/actions/terminate-delete",
                        Map.of("reason", "IT teardown of a finished incident case", "confirmToken", key),
                        String.class);
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(done.getBody()).path("deltaStatement").asText())
                .contains("irreversible");

        // the delete is synchronous — the runtime instance is gone
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            ResponseEntity<String> gone = as("viewer")
                    .getForEntity("/api/audit?engineId=engine-a-prod&action=terminate-delete", String.class);
            assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(mapper.readTree(gone.getBody()).toString()).contains(instanceId);
        });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> engine.get()
                        .uri("/runtime/process-instances/{id}", instanceId)
                        .retrieve()
                        .body(Map.class))
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
    }

    /* ------------------------------------------------------------------------------
     * Tier-1 reason discipline runs live on the prod twin: no reason → refused before
     * the engine; the same request on the dev registration proceeds (SPEC §6).
     * ------------------------------------------------------------------------------ */
    @Test
    void tierOneReasonIsRequiredOnTheProdTwinOnly() throws Exception {
        String instanceId = EngineSeed.startInstance(
                engine,
                "demoUserTask",
                businessKey + "-reason",
                List.of(Map.of("name", "amount", "type", "integer", "value", 7)));

        Map<String, Object> edit =
                Map.of("variable", Map.of("name", "amount", "type", "integer", "value", 8, "expectedOldValue", 7));
        ResponseEntity<String> refused = as("operator")
                .postForEntity(
                        "/api/instances/engine-a-prod/" + instanceId + "/actions/edit-variable", edit, String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(refused.getBody()).path("code").asText()).isEqualTo("reason-required");

        ResponseEntity<String> applied = as("operator")
                .postForEntity("/api/instances/engine-a/" + instanceId + "/actions/edit-variable", edit, String.class);
        assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
