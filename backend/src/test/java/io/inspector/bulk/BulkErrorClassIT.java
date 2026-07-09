package io.inspector.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.EngineSeed;
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
 * Rung 4 for the v1.x #1 group retry: the FULL arc against real flowable-rest 6.8 and a
 * real Postgres — two instances dead-letter with the SAME ArithmeticException signature,
 * the triage landing aggregates them into one error group, and ONE error-class submit
 * (coordinates only — no instance IDs cross the wire) resolves both server-side, runs
 * them through the M5 fan-out, and records the group provenance in the envelope audit row.
 *
 * <p>Isolation on the long-lived stack: a FRESH definition version is deployed per run and
 * the submit is version-scoped, so dead-letter residue from other ITs (same signature,
 * older versions) can never leak into the resolved member list.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml up -d   (flowable-6 profile)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BulkErrorClassIT {

    private static final String ENGINE =
            "http://localhost:" + System.getenv().getOrDefault("PI_ENGINE_A_PORT", "8081") + "/flowable-rest/service";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("inspector.it.bulk-error-class", () -> "true");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    private RestClient engine;
    private int version;

    @BeforeAll
    void seedOrganically() {
        engine = EngineSeed.requireReachable(ENGINE, "");
        EngineSeed.deployIfMissing(engine, "demoFailingPayment", EngineSeed.FAILING_PAYMENT_BPMN);
        version = EngineSeed.deployNewVersion(engine, "demoFailingPayment", EngineSeed.FAILING_PAYMENT_BPMN);
    }

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    @Test
    void groupRetryResolvesServerSideRetriesBothAndAuditsTheProvenance() throws Exception {
        String keyPrefix = "it-errorclass-" + UUID.randomUUID();
        String first = EngineSeed.startFailing(engine, "demoFailingPayment", keyPrefix + "-1");
        String second = EngineSeed.startFailing(engine, "demoFailingPayment", keyPrefix + "-2");
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> EngineSeed.deadLetterCountFor(engine, first) == 1
                        && EngineSeed.deadLetterCountFor(engine, second) == 1);

        // The signature comes from the SAME surface the operator reads it off: the triage
        // landing. refresh=true busts the 20s cache so the freshly dead-lettered pair is in.
        String signatureHash = signatureOfOurGroup();

        ResponseEntity<String> submit = as("responder")
                .postForEntity(
                        "/api/bulk/error-class",
                        Map.of(
                                "signatureHash",
                                signatureHash,
                                "algoVersion",
                                1,
                                "processDefinitionKey",
                                "demoFailingPayment",
                                "definitionVersion",
                                version,
                                "engineId",
                                "engine-a",
                                "reason",
                                "IT: group retry of the arithmetic error class"),
                        String.class);

        assertThat(submit.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode job = mapper.readTree(submit.getBody());
        String jobId = job.path("id").asText();
        assertThat(job.path("verb").asText()).isEqualTo("retry-job");
        assertThat(job.path("totalItems").asInt())
                .as("version-scoped resolution finds exactly our two instances")
                .isEqualTo(2);

        // The M5 fan-out is async: await the job settling with both items ok, through the
        // BFF's own job read (engine → resolution → dispatch → Postgres → API).
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    JsonNode state = mapper.readTree(as("responder")
                            .getForEntity("/api/bulk/" + jobId, String.class)
                            .getBody());
                    assertThat(state.path("state").asText()).isEqualTo("COMPLETED");
                    assertThat(state.path("tallies").path("ok").asLong()).isEqualTo(2);
                });

        // Envelope audit row (ONE per bulk, SPEC §7) carries the group provenance —
        // payload readable as OPERATOR on the ops log.
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            ResponseEntity<String> log =
                    as("operator").getForEntity("/api/audit?action=bulk:retry-job&limit=20", String.class);
            assertThat(log.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode rows = mapper.readTree(log.getBody());
            JsonNode envelope = null;
            for (JsonNode row : rows) {
                String payload = row.path("payload").asText("");
                if (payload.contains(jobId)) {
                    envelope = row;
                }
            }
            assertThat(envelope).as("envelope audit row for bulk job %s", jobId).isNotNull();
            JsonNode payload = mapper.readTree(envelope.path("payload").asText());
            JsonNode group = payload.path("errorClass");
            assertThat(group.path("signatureHash").asText()).isEqualTo(signatureHash);
            assertThat(group.path("algoVersion").asInt()).isEqualTo(1);
            assertThat(group.path("definition").asText()).isEqualTo("demoFailingPayment:v" + version);
            assertThat(group.path("engineId").asText()).isEqualTo("engine-a");
            assertThat(group.path("resolvedCount").asInt()).isEqualTo(2);
            assertThat(payload.path("targets"))
                    .extracting(JsonNode::asText)
                    .containsExactlyInAnyOrder("engine-a:" + first, "engine-a:" + second);
        });
    }

    @Test
    void viewerIsRefusedAtTheDoor() {
        ResponseEntity<String> response = as("viewer")
                .postForEntity(
                        "/api/bulk/error-class",
                        Map.of(
                                "signatureHash",
                                "0".repeat(64),
                                "algoVersion",
                                1,
                                "processDefinitionKey",
                                "demoFailingPayment",
                                "definitionVersion",
                                1,
                                "reason",
                                "viewer should never get this far"),
                        String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void drainedGroupAnswersConflictNotAnEmptyJob() {
        // A syntactically valid hash that matches nothing: the honest answer is 409
        // error-class-drained, never a zero-item job.
        ResponseEntity<String> response = as("responder")
                .postForEntity(
                        "/api/bulk/error-class",
                        Map.of(
                                "signatureHash",
                                "f".repeat(64),
                                "algoVersion",
                                1,
                                "processDefinitionKey",
                                "demoFailingPayment",
                                "definitionVersion",
                                version,
                                "engineId",
                                "engine-a",
                                "reason",
                                "IT: drained-group refusal probe"),
                        String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("error-class-drained");
    }

    /**
     * Reads the triage landing exactly like the card does and returns the signature of the
     * group whose engine-a counts contain our fresh {@code demoFailingPayment:vN} pair.
     */
    private String signatureOfOurGroup() throws Exception {
        String defVersionKey = "demoFailingPayment:v" + version;
        // Triage is cached and the DLQ rows just landed — poll the refreshed dashboard
        // until the version-scoped pair shows up in an error group.
        final String[] hash = new String[1];
        await().atMost(30, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).untilAsserted(() -> {
            ResponseEntity<String> triage = as("viewer").getForEntity("/api/triage?refresh=true", String.class);
            assertThat(triage.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode groups = mapper.readTree(triage.getBody()).path("errorGroups");
            for (JsonNode group : groups) {
                JsonNode counts = group.path("countsByEngine").path("engine-a").path(defVersionKey);
                if (counts.asLong(0) >= 2) {
                    hash[0] = group.path("signatureHash").asText();
                    return;
                }
            }
            assertThat(hash[0])
                    .as("an error group holding 2× %s on engine-a", defVersionKey)
                    .isNotNull();
        });
        return hash[0];
    }
}
