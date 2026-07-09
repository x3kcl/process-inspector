package io.inspector.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.EngineSeed;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
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
 * Rung 4 (Case Inspector Phase 3): the two CMMN dead-letter verbs — retry (tier 0 / RESPONDER) and
 * delete (tier 3 / ADMIN) — run through the SAME verb executor as BPMN, proven against the REAL
 * flowable-6 (6.8) engine and a REAL Postgres 16 (Testcontainers), no mocked wire (engine-harness).
 * The scope seam is the only difference: both hit the {@code /cmmn-management} DLQ (byte-identical
 * bodies, sibling context; move + delete spiked live 2026-07-08, HTTP 204 each) and audit the
 * caseInstanceId.
 *
 * <p>Two deliberately-failing cases are seeded (one per verb) so the terminal delete never races
 * the retry over a single shared job; both fail concurrently, so the await stays ~40s, not ~80s.
 * Arc: await each case's async task dead-lettering (keyed on its own caseInstanceId, residue-proof)
 * → a RESPONDER retries case A's job (leaves the DLQ) / an ADMIN deletes case B's job (gone for
 * good) → outcome {@code ok} and the action is in the audit golden master.
 *
 * <p>Hermetic teardown: both cases are terminated and the deployment cascade-deleted so no
 * dead-lettered CMMN case survives to skew a parallel session's pristine DLQ counts.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml up -d   (flowable-6 profile)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CmmnCorrectiveActionIT {

    private static final String ENGINE =
            "http://localhost:" + System.getenv().getOrDefault("PI_ENGINE_A_PORT", "8081") + "/flowable-rest/service";
    private static final String CASE_KEY = "demoFailingCase";

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

    private RestClient cmmn;
    private String retryCaseId;
    private String deleteCaseId;

    @BeforeAll
    void seedFailingCase() {
        EngineSeed.requireReachable(ENGINE, "");
        cmmn = EngineSeed.cmmnClient(ENGINE);
        EngineSeed.deployCmmnIfMissing(cmmn, CASE_KEY, EngineSeed.FAILING_CASE_CMMN);
        retryCaseId = EngineSeed.startFailingCase(cmmn, CASE_KEY);
        deleteCaseId = EngineSeed.startFailingCase(cmmn, CASE_KEY);

        // The CMMN engine ignores failedJobRetryTimeCycle → default retry budget (~30-40s live).
        // Engine truth, keyed on each run's own caseInstanceId (residue-proof) — never a sleep.
        // Both cases fail concurrently, so the second await returns right after the first.
        await().atMost(75, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .until(() -> EngineSeed.cmmnDeadletterPresentForCase(cmmn, retryCaseId)
                        && EngineSeed.cmmnDeadletterPresentForCase(cmmn, deleteCaseId));

        // The CMMN retry capability-gates on scopeType from the scheduled probe — wait one cycle so
        // the registry reports engine-a reachable + capable before we fire the mutation.
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(rest.withBasicAuth("admin", "dev")
                                .getForObject("/api/engines", JsonNode.class)
                                .get(0)
                                .get("reachable")
                                .asBoolean())
                        .isTrue());
    }

    @AfterAll
    void teardown() {
        if (cmmn != null) {
            EngineSeed.deleteCaseQuietly(cmmn, retryCaseId);
            EngineSeed.deleteCaseQuietly(cmmn, deleteCaseId);
            EngineSeed.deleteCmmnDeploymentsQuietly(cmmn, CASE_KEY);
        }
    }

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    @Test
    void responderRetriesTheCmmnDeadLetterJobWhichLeavesTheDlqAndIsAudited() throws Exception {
        String jobId = EngineSeed.cmmnDeadLetterJobIdFor(cmmn, retryCaseId);

        ResponseEntity<String> response = as("responder")
                .postForEntity(
                        "/api/cases/engine-a/" + retryCaseId + "/actions/retry-job",
                        Map.of("jobId", jobId),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode result = mapper.readTree(response.getBody());
        assertThat(result.path("outcome").asText()).isEqualTo("ok");
        assertThat(result.path("deltaStatement").asText()).contains("executable queue");
        assertThat(result.path("auditId").asText()).isNotBlank();

        // The move is synchronous (HTTP 204 before returning): the CMMN DLQ no longer holds this
        // case's job. A re-dead-letter needs fresh failures through the retry budget (~18s+ live),
        // far outside this immediate check.
        assertThat(EngineSeed.cmmnDeadletterPresentForCase(cmmn, retryCaseId))
                .as("the retried job moved out of the CMMN dead-letter lane")
                .isFalse();

        // …and the audit golden master has the row (instance_id is scope-neutral, so the case id
        // reads back through the instance-audit endpoint) — engine → service → Postgres → API.
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            ResponseEntity<String> audit =
                    as("operator").getForEntity("/api/instances/engine-a/" + retryCaseId + "/audit", String.class);
            assertThat(audit.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode rows = mapper.readTree(audit.getBody());
            assertThat(rows).isNotEmpty();
            JsonNode row = rows.get(0);
            assertThat(row.path("action").asText()).isEqualTo("retry-job");
            assertThat(row.path("actor").asText()).isEqualTo("responder");
            assertThat(row.path("outcome").asText()).isEqualTo("ok");
            assertThat(row.path("payload").asText()).contains(jobId).contains("cmmn");
        });
    }

    @Test
    void adminDeletesTheCmmnDeadLetterJobWhichIsGoneForGoodAndIsAudited() throws Exception {
        String jobId = EngineSeed.cmmnDeadLetterJobIdFor(cmmn, deleteCaseId);

        // Delete is tier 3 → a reason is mandatory (engine-a is DEV, so no typed confirm token).
        ResponseEntity<String> response = as("admin")
                .postForEntity(
                        "/api/cases/engine-a/" + deleteCaseId + "/actions/delete-deadletter",
                        Map.of("jobId", jobId, "reason", "case abandoned; job orphaned deliberately (IT)"),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode result = mapper.readTree(response.getBody());
        assertThat(result.path("outcome").asText()).isEqualTo("ok");
        // scope-honest delta: a CMMN case has no change-state rescue in this tool
        assertThat(result.path("deltaStatement").asText()).contains("orphaned").contains("no change-state for cases");
        assertThat(result.path("auditId").asText()).isNotBlank();

        // Delete is terminal (DELETE → HTTP 204): the job is gone from the CMMN DLQ for good —
        // unlike retry, it never re-dead-letters.
        assertThat(EngineSeed.cmmnDeadletterPresentForCase(cmmn, deleteCaseId))
                .as("the deleted job is gone from the CMMN dead-letter lane")
                .isFalse();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            ResponseEntity<String> audit =
                    as("operator").getForEntity("/api/instances/engine-a/" + deleteCaseId + "/audit", String.class);
            assertThat(audit.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode rows = mapper.readTree(audit.getBody());
            assertThat(rows).isNotEmpty();
            JsonNode row = rows.get(0);
            assertThat(row.path("action").asText()).isEqualTo("delete-deadletter");
            assertThat(row.path("actor").asText()).isEqualTo("admin");
            assertThat(row.path("outcome").asText()).isEqualTo("ok");
            assertThat(row.path("payload").asText()).contains(jobId).contains("cmmn");
        });
    }
}
