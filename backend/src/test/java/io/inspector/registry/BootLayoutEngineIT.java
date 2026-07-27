package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.config.EngineApiContexts;
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
 * Rung 4 against an engine that is an APPLICATION, not the standalone {@code flowable-rest} war —
 * the shape this inspector runs against in production (flap on hp02) and the one every other IT
 * misses. Two things are only true here:
 *
 * <ol>
 *   <li><b>The Boot layout.</b> The registered base-url ends in {@code /process-api}, so the CMMN
 *       and external-worker contexts are its ROOT-level siblings, not {@code …/flowable-rest/*}.
 *       {@link EngineApiContexts} derives them; this class proves the derivation lands on
 *       endpoints that really answer, which is precisely what a unit test over strings cannot.</li>
 *   <li><b>An application's own auth.</b> No well-known {@code rest-admin/test} — flap admits a
 *       dedicated Basic-auth service account on {@code /process-api} and nothing else. Before that
 *       chain existed the engine answered every REST call with a 302 to its login page, which a
 *       redirect-following client reads as a 200 carrying HTML: an engine that looks up while
 *       returning nothing usable. The health leg below is what catches a regression there.</li>
 * </ol>
 *
 * <p>The arc is the product one end to end: flap runs a process, the process dead-letters
 * organically, the inspector's Stage-0 join sees it on the failed lane, an operator retries it
 * through the BFF, and the job leaves the dead-letter queue. Fixtures are seeded strictly over
 * REST (never into {@code ACT_*}); every wait is Awaitility against real engine/BFF state.
 *
 * <p>Requires: {@code docker compose -f docker/docker-compose.dev.yml --profile flap up -d}
 * (plus {@code PI_FLAP_IMAGE=…} when working from a local flap build — see that file's header).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_FLAP_PASSWORD=harness")
@ActiveProfiles("it-flap")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BootLayoutEngineIT {

    private static final String PORT = System.getenv().getOrDefault("PI_ENGINE_FLAP_PORT", "8086");
    private static final String BASE = "http://localhost:" + PORT + "/process-api";
    private static final String COMPOSE_HINT = "--profile flap";
    private static final String ENGINE_ID = "engine-flap";

    /** The service account flap's /process-api chain admits — see the compose profile. */
    private static final String SERVICE_USER = "inspector";

    private static final String SERVICE_PASSWORD = "harness";

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
    EngineHealthService healthService;

    @Autowired
    ObjectMapper mapper;

    private RestClient engine;
    private final String businessKey = "it-flap-" + UUID.randomUUID();

    @BeforeAll
    void seedOrganically() {
        engine = EngineSeed.requireReachable(
                EngineSeed.engineClient(BASE, SERVICE_USER, SERVICE_PASSWORD), BASE, COMPOSE_HINT);
        EngineSeed.deployIfMissing(engine, "demoFailingPayment", EngineSeed.FAILING_PAYMENT_BPMN);
    }

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    /* ------------------------------------------------------------------------------
     * 1. The engine answers at all — Basic auth on /process-api, and a REST answer
     *    rather than a login page.
     * ------------------------------------------------------------------------------ */
    @Test
    void theServiceAccountIsAcceptedAndTheProbeReadsARealEngine() throws Exception {
        healthService.probeAll();

        JsonNode dto = mapper.readTree(rest.withBasicAuth("admin", "dev").getForObject("/api/engines", String.class))
                .get(0);
        assertThat(dto.get("id").asText()).isEqualTo(ENGINE_ID);
        assertThat(dto.get("reachable").asBoolean()).isTrue();
        // An embedded Flowable 7 engine. A login page would have parsed as neither.
        assertThat(dto.get("engineVersion").asText()).startsWith("7.");
        assertThat(dto.get("healthError").isNull()).isTrue();

        // An embedded Flowable 7 engine clears every version cliff, exactly like the 7.x war.
        JsonNode caps = dto.get("capabilities");
        for (String cap :
                new String[] {"changeState", "migration", "externalWorkerJobs", "scopeType", "activityHistory"}) {
            assertThat(caps.get(cap).asBoolean()).as(cap).isTrue();
        }
    }

    /**
     * Wrong credentials must be a VISIBLE failure. This is the assertion that would have caught the
     * pre-fix 302: a redirect-following client sees 200 + HTML, so "unreachable" would never have
     * been reported — the engine would have looked healthy and returned nothing.
     */
    @Test
    void badCredentialsFailLoudlyRatherThanReturningALoginPage() {
        RestClient impostor = EngineSeed.engineClient(BASE, SERVICE_USER, "not-the-password");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                        impostor.get().uri("/management/engine").retrieve().toBodilessEntity()))
                .isNotNull()
                .hasMessageContaining("401");
    }

    /* ------------------------------------------------------------------------------
     * 2. The derived sibling contexts land on endpoints that really exist.
     * ------------------------------------------------------------------------------ */
    @Test
    void theDerivedSiblingContextsResolveOnTheBootLayout() {
        assertThat(EngineApiContexts.externalJobApi(BASE)).isEqualTo("http://localhost:" + PORT + "/external-job-api");
        assertThat(EngineApiContexts.cmmnApi(BASE)).isEqualTo("http://localhost:" + PORT + "/cmmn-api");

        // 200s, not the 404s the pre-fix `…/process-api/cmmn-api` derivation produced.
        RestClient external =
                EngineSeed.engineClient(EngineApiContexts.externalJobApi(BASE), SERVICE_USER, SERVICE_PASSWORD);
        assertThat(external.get()
                        .uri("/jobs?size=1")
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        RestClient cmmn = EngineSeed.engineClient(EngineApiContexts.cmmnApi(BASE), SERVICE_USER, SERVICE_PASSWORD);
        assertThat(cmmn.get()
                        .uri("/cmmn-management/deadletter-jobs?size=1")
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /* ------------------------------------------------------------------------------
     * 3. The product arc: flap fails a job, the inspector sees it, an operator fixes it.
     * ------------------------------------------------------------------------------ */
    @Test
    void aDeadLetteredInstanceIsSeenOnTheFailedLaneAndRetriedThroughTheInspector() throws Exception {
        String instanceId = EngineSeed.startFailing(engine, "demoFailingPayment", businessKey + "-retry");

        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> EngineSeed.deadLetterCountFor(engine, instanceId) == 1);
        String jobId = EngineSeed.deadLetterJobIdFor(engine, instanceId);

        // Stage 0 counts the job on the dead-letter lane of THIS engine — the join reading the
        // Boot layout's /management/* endpoints, not just the war layout's.
        healthService.probeAll();
        JsonNode lanes = mapper.readTree(rest.withBasicAuth("admin", "dev").getForObject("/api/engines", String.class))
                .get(0)
                .get("jobLanes");
        assertThat(lanes.get("deadletter").asLong()).isGreaterThanOrEqualTo(1);

        // …and the instance detail the operator would be looking at resolves on this engine.
        ResponseEntity<String> detail =
                as("operator").getForEntity("/api/instances/" + ENGINE_ID + "/" + instanceId, String.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> response = as("responder")
                .postForEntity(
                        "/api/instances/" + ENGINE_ID + "/" + instanceId + "/actions/retry-job",
                        Map.of("jobId", jobId),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode result = mapper.readTree(response.getBody());
        assertThat(result.path("outcome").asText()).isEqualTo("ok");
        assertThat(result.path("auditId").asText()).isNotBlank();

        // The move is synchronous — the job is back on the executable queue.
        assertThat(EngineSeed.deadLetterCountFor(engine, instanceId)).isZero();

        // The audit golden master carries it, attributed to the actor who pressed the button.
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            JsonNode rows = mapper.readTree(as("operator")
                    .getForEntity("/api/instances/" + ENGINE_ID + "/" + instanceId + "/audit", String.class)
                    .getBody());
            assertThat(rows).isNotEmpty();
            assertThat(rows.get(0).path("action").asText()).isEqualTo("retry-job");
            assertThat(rows.get(0).path("actor").asText()).isEqualTo("responder");
            assertThat(rows.get(0).path("outcome").asText()).isEqualTo("ok");
        });
    }
}
