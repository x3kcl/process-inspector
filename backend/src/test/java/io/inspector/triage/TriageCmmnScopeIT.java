package io.inspector.triage;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.inspector.support.EngineSeed;
import io.inspector.support.NoDbTestSupport;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Rung 4: the CMMN out-of-scope reconciliation, proven against the REAL flowable-6 engine.
 * flowable-rest shares its job tables with the co-deployed CMMN engine, and the process-api
 * DLQ projection (the lane the Stage-0 aggregator scans) lists a CMMN dead-letter job as a
 * {@code processInstanceId:null} orphan — verified live 2026-07-07 (cmmn-wire-shape-spike).
 * Before this fix that orphan was silently dropped, so the health strip's dead-letter lane
 * count out-ran the process-scoped FAILED count with no explanation. The proof here is that
 * the seeded failing CASE:
 *
 * <ul>
 *   <li>is COUNTED — {@code perEngine.engine-a.outOfScopeDeadletters ≥ 1} (a number, not
 *       null: the {@code scopeType} capability gate opened on 6.8);</li>
 *   <li>is NOT MISCOUNTED — its exception forms no drillable error group (the orphan never
 *       rolls up as a phantom process failure).</li>
 * </ul>
 *
 * Hermetic teardown: the case is terminated and its deployment cascade-deleted, so no
 * dead-lettered CMMN case survives to skew a parallel session's pristine DLQ counts.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml up -d   (flowable-6 profile)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-triage")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(NoDbTestSupport.class)
class TriageCmmnScopeIT {

    private static final String ENGINE = "http://localhost:8081/flowable-rest/service";
    private static final String CASE_KEY = "demoFailingCase";
    /** The seed's unique failing expression — a residue-independent needle for the orphan. */
    private static final String NEEDLE = "nonExistentBean";

    private static final WireMockServer proxy = new WireMockServer(options().dynamicPort());

    @DynamicPropertySource
    static void registerProxy(DynamicPropertyRegistry registry) {
        proxy.start();
        proxy.stubFor(any(anyUrl()).willReturn(aResponse().proxiedFrom("http://localhost:8081")));
        registry.add("TRIAGE_PROXY_PORT", () -> proxy.port());
    }

    @Autowired
    TestRestTemplate rest;

    private RestClient engine;
    private RestClient cmmn;
    private String caseInstanceId;

    @BeforeAll
    void seedFailingCase() {
        rest = rest.withBasicAuth("admin", "dev"); // the BFF requires auth on every /api route
        engine = EngineSeed.requireReachable(ENGINE, "");
        cmmn = EngineSeed.cmmnClient(ENGINE);

        EngineSeed.deployCmmnIfMissing(cmmn, CASE_KEY, EngineSeed.FAILING_CASE_CMMN);
        caseInstanceId = EngineSeed.startFailingCase(cmmn, CASE_KEY);

        // The async service task dead-letters once its DEFAULT retry budget exhausts (6.8's
        // CMMN engine ignores failedJobRetryTimeCycle — ~30-40s, measured live). Await the
        // ORPHAN as the process-api projection renders it — engine truth, never a sleep.
        await().atMost(75, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .until(() -> EngineSeed.outOfScopeDeadletterPresent(engine, NEEDLE));

        // The health strip (and the scopeType capability the count is gated on) comes from
        // the scheduled M1 probe — wait for one cycle before triage reads the registry.
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(rest.getForObject("/api/engines", JsonNode.class)
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
        proxy.stop();
    }

    @Test
    void cmmnDeadletterIsCountedOutOfScopeAndNeverAsAPhantomProcessFailure() {
        JsonNode body = rest.getForObject("/api/triage", JsonNode.class);

        // The 6.8 engine can discriminate scope — the capability gate is open.
        assertThat(body.get("engines")
                        .get(0)
                        .get("capabilities")
                        .get("scopeType")
                        .asBoolean())
                .as("engine-a is Flowable 6.8 — scopeType capability present")
                .isTrue();

        JsonNode perEngine = body.get("perEngine").get("engine-a");
        assertThat(perEngine.get("ok").asBoolean()).isTrue();

        // COUNTED: the shared-table CMMN orphan is surfaced, not silently dropped. A number
        // (gate open), never null; ≥1 because we seeded exactly one and cleaned residue.
        JsonNode outOfScope = perEngine.get("outOfScopeDeadletters");
        assertThat(outOfScope.isNull())
                .as("scopeType-capable engine reports a concrete out-of-scope count, not unknown")
                .isFalse();
        assertThat(outOfScope.asInt())
                .as("the seeded failing CASE is one out-of-scope dead-letter")
                .isGreaterThanOrEqualTo(1);

        // NOT MISCOUNTED: the CMMN exception must not roll up as a drillable process error
        // group — the exact orphan-row bug this fix closes.
        body.get("errorGroups").forEach(group -> {
            assertThat(group.get("sampleRawMessage").asText(""))
                    .as("out-of-scope failure is never a phantom error group")
                    .doesNotContain(NEEDLE);
            assertThat(group.get("normalizedMessage").asText("")).doesNotContain(NEEDLE);
        });
    }

    /**
     * Case Inspector Phase 1: the Stage-0 scalar becomes a drillable list. The dedicated
     * endpoint enumerates the CMMN dead-letter jobs from the cmmn-api projection (non-null
     * {@code caseInstanceId}), and the seeded failing CASE appears there — same underlying
     * dead-letter job the process-api projection counts as an orphan, now with its full case
     * attribution for a drill-down.
     */
    @Test
    void outOfScopeDeadlettersEndpointEnumeratesTheSeededCase() {
        JsonNode body = rest.getForObject("/api/triage/engines/engine-a/out-of-scope-deadletters", JsonNode.class);

        assertThat(body.get("truncated").asBoolean())
                .as("the tiny seeded DLQ is well under dlq-scan-cap")
                .isFalse();

        JsonNode jobs = body.get("jobs");
        assertThat(jobs.isArray()).isTrue();
        JsonNode seeded = null;
        for (JsonNode job : jobs) {
            if (caseInstanceId.equals(job.path("caseInstanceId").asText())) {
                seeded = job;
                break;
            }
        }
        assertThat(seeded)
                .as("the seeded failing case is enumerated as a CMMN dead-letter row")
                .isNotNull();
        // Every enumerated row is genuinely CMMN-scoped — the discriminator held.
        jobs.forEach(job -> assertThat(job.path("caseInstanceId").asText())
                .as("no BPMN row leaks into the out-of-scope list")
                .isNotBlank());
        assertThat(seeded.path("exceptionMessage").asText()).contains(NEEDLE);
        assertThat(seeded.path("retries").asInt()).isZero();
    }

    /** VIEWER floor holds: an unauthenticated caller is rejected before any engine read. */
    @Test
    void outOfScopeDeadlettersEndpointRequiresAuth() {
        assertThat(rest.withBasicAuth("nobody", "wrong")
                        .getForEntity("/api/triage/engines/engine-a/out-of-scope-deadletters", String.class)
                        .getStatusCode()
                        .is4xxClientError())
                .isTrue();
    }
}
