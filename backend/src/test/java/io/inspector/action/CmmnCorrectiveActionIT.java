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
 * Rung 4 (Case Inspector Phase 3): a CMMN dead-letter job is retried through the SAME verb
 * executor as BPMN — proven against the REAL flowable-6 (6.8) engine and a REAL Postgres 16
 * (Testcontainers), no mocked wire (engine-harness). The scope seam is the only difference: the
 * retry hits the {@code /cmmn-management} DLQ move (byte-identical body, sibling context; spike
 * 2026-07-08) and audits the caseInstanceId.
 *
 * <p>Arc: seed the deliberately-failing case → await its async task dead-lettering (keyed on this
 * run's caseInstanceId, residue-proof) → a RESPONDER retries the CMMN dead-letter job → outcome
 * {@code ok}, the job leaves the CMMN DLQ (the move is synchronous; the re-dead-letter needs fresh
 * failures far outside this check), and the action is in the audit golden master.
 *
 * <p>Hermetic teardown: the case is terminated and its deployment cascade-deleted so no
 * dead-lettered CMMN case survives to skew a parallel session's pristine DLQ counts.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml up -d   (flowable-6 profile)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CmmnCorrectiveActionIT {

    private static final String ENGINE = "http://localhost:8081/flowable-rest/service";
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
    private String caseInstanceId;

    @BeforeAll
    void seedFailingCase() {
        EngineSeed.requireReachable(ENGINE, "");
        cmmn = EngineSeed.cmmnClient(ENGINE);
        EngineSeed.deployCmmnIfMissing(cmmn, CASE_KEY, EngineSeed.FAILING_CASE_CMMN);
        caseInstanceId = EngineSeed.startFailingCase(cmmn, CASE_KEY);

        // The CMMN engine ignores failedJobRetryTimeCycle → default retry budget (~30-40s live).
        // Engine truth, keyed on THIS run's caseInstanceId (residue-proof) — never a sleep.
        await().atMost(75, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .until(() -> EngineSeed.cmmnDeadletterPresentForCase(cmmn, caseInstanceId));

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
            EngineSeed.deleteCaseQuietly(cmmn, caseInstanceId);
            EngineSeed.deleteCmmnDeploymentsQuietly(cmmn, CASE_KEY);
        }
    }

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    @Test
    void responderRetriesTheCmmnDeadLetterJobWhichLeavesTheDlqAndIsAudited() throws Exception {
        String jobId = EngineSeed.cmmnDeadLetterJobIdFor(cmmn, caseInstanceId);

        ResponseEntity<String> response = as("responder")
                .postForEntity(
                        "/api/cases/engine-a/" + caseInstanceId + "/actions/retry-job",
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
        assertThat(EngineSeed.cmmnDeadletterPresentForCase(cmmn, caseInstanceId))
                .as("the retried job moved out of the CMMN dead-letter lane")
                .isFalse();

        // …and the audit golden master has the row (instance_id is scope-neutral, so the case id
        // reads back through the instance-audit endpoint) — engine → service → Postgres → API.
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            ResponseEntity<String> audit =
                    as("operator").getForEntity("/api/instances/engine-a/" + caseInstanceId + "/audit", String.class);
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
}
