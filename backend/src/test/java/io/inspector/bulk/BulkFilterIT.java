package io.inspector.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.EngineSeed;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Rung 4 for the v1.x #2 filter bulk: the FULL arc against real flowable-rest 6.8 and a
 * real Postgres — twelve instances dead-letter on a FRESH definition version, ONE filter
 * submit (criteria only — no instance IDs cross the wire) re-resolves them server-side
 * through the exhaustive M2a plan (max-page-size is 10 here, so 12 members PROVE the
 * pagination), records the member list + criteria in the envelope audit row, trickles the
 * fan-out through the stagger, and pushes id-only {@code bulk-job} events on the SSE
 * stream while the drawer-style polling read converges.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml up -d   (flowable-6 profile)
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ENGINE_A_PASSWORD=test",
            // Stagger small enough to keep the IT fast, large enough to be real pacing.
            "inspector.bulk.stagger-ms=50",
            "inspector.bulk.engine-permits=2"
        })
@ActiveProfiles("it-actions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BulkFilterIT {

    private static final String ENGINE = "http://localhost:8081/flowable-rest/service";
    private static final int MEMBERS = 12; // > max-page-size 10 → exhaustive paging is exercised

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("inspector.it.bulk-filter", () -> "true");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @LocalServerPort
    int port;

    private RestClient engine;
    private int version;
    private HttpURLConnection sseConnection;
    private final List<String> seededInstances = new ArrayList<>();

    @BeforeAll
    void seedOrganically() {
        engine = EngineSeed.requireReachable(ENGINE, "");
        EngineSeed.deployIfMissing(engine, "demoFailingPayment", EngineSeed.FAILING_PAYMENT_BPMN);
        version = EngineSeed.deployNewVersion(engine, "demoFailingPayment", EngineSeed.FAILING_PAYMENT_BPMN);
    }

    @AfterAll
    void cleanUp() {
        if (sseConnection != null) {
            sseConnection.disconnect();
        }
        // Residue hygiene: this IT seeds 12 failing instances per run — on the KEEP-up
        // stack they re-deadletter after the retry and would pile toward the capped scans
        // other ITs rely on. Delete OUR OWN instances; nothing else.
        for (String id : seededInstances) {
            EngineSeed.deleteInstanceQuietly(engine, id);
        }
    }

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    @Test
    void filterBulkResolvesAcrossPagesStaggersTheFanOutAndStreamsProgress() throws Exception {
        // ---- seed 12 dead-lettered members of the fresh version ----
        String keyPrefix = "it-filter-" + UUID.randomUUID();
        List<String> seeded = new ArrayList<>();
        for (int i = 0; i < MEMBERS; i++) {
            seeded.add(EngineSeed.startFailing(engine, "demoFailingPayment", keyPrefix + "-" + i));
        }
        seededInstances.addAll(seeded);
        await().atMost(120, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> seeded.stream().allMatch(id -> EngineSeed.deadLetterCountFor(engine, id) == 1));

        // ---- attach the SSE stream FIRST (id-only contract: names + job id, no bodies) ----
        ConcurrentLinkedQueue<String> sseLines = attachEventStream();

        // ---- submit criteria, never IDs ----
        Map<String, Object> criteria = Map.of(
                "engineIds",
                List.of("engine-a"),
                "statuses",
                List.of("FAILED"),
                "processDefinitionKey",
                "demoFailingPayment",
                "definitionVersion",
                version);
        ResponseEntity<String> submit = as("responder")
                .postForEntity(
                        "/api/bulk/filter",
                        Map.of(
                                "criteria", criteria,
                                "verb", "retry-job",
                                "reason", "IT: select-all-matching-filter retry"),
                        String.class);

        assertThat(submit.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode job = mapper.readTree(submit.getBody());
        String jobId = job.path("id").asText();
        assertThat(job.path("totalItems").asInt())
                .as("exhaustive resolution pages past max-page-size 10")
                .isEqualTo(MEMBERS);
        // The stagger paces 12 dispatches ≥ ~550ms — the submit answered BEFORE the fan-out
        // finished, i.e. the pacing never blocks the request thread.
        assertThat(job.path("state").asText()).isEqualTo("PENDING");

        // ---- the fan-out settles; every member retried ----
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    JsonNode state = mapper.readTree(as("responder")
                            .getForEntity("/api/bulk/" + jobId, String.class)
                            .getBody());
                    assertThat(state.path("state").asText()).isEqualTo("COMPLETED");
                    assertThat(state.path("tallies").path("ok").asLong()).isEqualTo(MEMBERS);
                });

        // ---- the stream carried id-only bulk-job events for THIS job ----
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(sseLines).anySatisfy(line -> assertThat(line).isEqualTo("event:bulk-job"));
            assertThat(sseLines).anySatisfy(line -> assertThat(line).isEqualTo("data:" + jobId));
        });

        // ---- envelope audit row: criteria + resolved member list recorded BEFORE dispatch ----
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            ResponseEntity<String> log =
                    as("operator").getForEntity("/api/audit?action=bulk:retry-job&limit=50", String.class);
            assertThat(log.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode envelope = null;
            for (JsonNode row : mapper.readTree(log.getBody())) {
                if (row.path("payload").asText("").contains(jobId)) {
                    envelope = row;
                }
            }
            assertThat(envelope).as("envelope audit row for bulk job %s", jobId).isNotNull();
            JsonNode payload = mapper.readTree(envelope.path("payload").asText());
            JsonNode filter = payload.path("filter");
            assertThat(filter.path("resolvedCount").asInt()).isEqualTo(MEMBERS);
            assertThat(filter.path("criteria").path("processDefinitionKey").asText())
                    .isEqualTo("demoFailingPayment");
            assertThat(filter.path("criteria").path("definitionVersion").asInt())
                    .isEqualTo(version);
            List<String> expected = seeded.stream().map(id -> "engine-a:" + id).toList();
            assertThat(payload.path("targets"))
                    .extracting(JsonNode::asText)
                    .containsExactlyInAnyOrderElementsOf(expected);
        });
    }

    @Test
    void viewerIsRefusedAtTheDoor() {
        ResponseEntity<String> response = as("viewer")
                .postForEntity(
                        "/api/bulk/filter",
                        Map.of(
                                "criteria", Map.of("statuses", List.of("FAILED")),
                                "verb", "retry-job",
                                "reason", "viewer should never get this far"),
                        String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void completedChipRefusedAsNotActionable() {
        ResponseEntity<String> response = as("responder")
                .postForEntity(
                        "/api/bulk/filter",
                        Map.of(
                                "criteria",
                                        Map.of(
                                                "engineIds", List.of("engine-a"),
                                                "statuses", List.of("ACTIVE", "COMPLETED")),
                                "verb", "suspend",
                                "reason", "IT: completed-chip refusal probe"),
                        String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("filter-completed-not-actionable");
    }

    /**
     * Raw line reader on GET /api/bulk/events — the wire, no wrappers. A DAEMON thread on
     * a plain URLConnection: the stream stays open for the whole class (SseEmitter window
     * is 30 min) and must never keep the surefire fork JVM alive after System.exit.
     */
    private ConcurrentLinkedQueue<String> attachEventStream() {
        ConcurrentLinkedQueue<String> lines = new ConcurrentLinkedQueue<>();
        String basic = "Basic " + Base64.getEncoder().encodeToString("responder:dev".getBytes(StandardCharsets.UTF_8));
        Thread reader = new Thread(
                () -> {
                    try {
                        HttpURLConnection connection =
                                (HttpURLConnection) URI.create("http://localhost:" + port + "/api/bulk/events")
                                        .toURL()
                                        .openConnection();
                        sseConnection = connection;
                        connection.setRequestProperty("Authorization", basic);
                        connection.setRequestProperty("Accept", "text/event-stream");
                        try (BufferedReader in = new BufferedReader(
                                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = in.readLine()) != null) {
                                lines.add(line);
                            }
                        }
                    } catch (IOException e) {
                        // stream teardown at JVM exit — nothing to assert here
                    }
                },
                "it-sse-reader");
        reader.setDaemon(true);
        reader.start();
        return lines;
    }
}
