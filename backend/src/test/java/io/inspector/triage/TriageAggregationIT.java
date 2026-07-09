package io.inspector.triage;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.inspector.support.EngineSeed;
import io.inspector.support.NoDbTestSupport;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Rung 4: GET /api/triage against the REAL flowable-6 engine, reached through a
 * TRANSPARENT WireMock proxy — every byte is the live engine's wire shape (never a
 * stub; engine-harness iron rule), and the proxy's request journal is the hard proof
 * of the two binding doctrines:
 *
 * <ul>
 *   <li><b>Aggregation-independence</b> — every status-count query carries
 *       {@code size:1} (totals only, no rows; never the grid-search plan);</li>
 *   <li><b>Thundering-herd protection</b> — a second GET inside the 20s TTL makes ZERO
 *       additional engine calls; {@code refresh=true} bypasses once per throttle
 *       interval.</li>
 * </ul>
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml up -d   (flowable-6 profile)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-triage")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
// M4: docker-free profile — DB autoconfig excluded, repositories mocked (NoDbTestSupport).
@Import(NoDbTestSupport.class)
class TriageAggregationIT {

    private static final String ENGINE =
            "http://localhost:" + System.getenv().getOrDefault("PI_ENGINE_A_PORT", "8081") + "/flowable-rest/service";

    private static final WireMockServer proxy = new WireMockServer(options().dynamicPort());

    @DynamicPropertySource
    static void registerProxy(DynamicPropertyRegistry registry) {
        proxy.start();
        proxy.stubFor(any(anyUrl())
                .willReturn(aResponse()
                        .proxiedFrom("http://localhost:" + System.getenv().getOrDefault("PI_ENGINE_A_PORT", "8081"))));
        registry.add("TRIAGE_PROXY_PORT", () -> proxy.port());
    }

    @AfterAll
    static void stopProxy() {
        proxy.stop();
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    private RestClient engine;
    private int versionA;
    private int versionB;

    @BeforeAll
    void seedOrganically() {
        rest = rest.withBasicAuth("admin", "dev"); // M4: the BFF now requires auth on every /api route
        engine = EngineSeed.requireReachable(ENGINE, "");
        EngineSeed.deployIfMissing(engine, "demoFailingPayment", EngineSeed.FAILING_PAYMENT_BPMN);
        EngineSeed.deployIfMissing(engine, "demoFailingRetry", EngineSeed.FAILING_RETRY_BPMN);
        EngineSeed.deployIfMissing(engine, "demoUserTask", EngineSeed.USER_TASK_BPMN);
        EngineSeed.deployIfMissing(engine, "demoOrder", EngineSeed.ORDER_BPMN);

        // Two definition VERSIONS with organic dead-letters each — the per-version split.
        versionA = EngineSeed.latestVersion(engine, "demoFailingPayment");
        String failingOnA = EngineSeed.startFailingPayment(engine);
        versionB = EngineSeed.deployNewVersion(engine, "demoFailingPayment", EngineSeed.FAILING_PAYMENT_BPMN);
        String failingOnB = EngineSeed.startFailingPayment(engine);
        // RETRYING tier: pinned failing-with-retries-left (timer lane, withException).
        String retrying = EngineSeed.startFailing(engine, "demoFailingRetry", null);
        // One SUSPENDED and one COMPLETED so every status-count leg has evidence.
        String parked = EngineSeed.startInstance(engine, "demoUserTask", null, List.of());
        EngineSeed.suspend(engine, parked);
        EngineSeed.startCompletedOrder(engine);

        await().atMost(60, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(EngineSeed.deadLetterCountFor(engine, failingOnA)).isGreaterThanOrEqualTo(1);
            assertThat(EngineSeed.deadLetterCountFor(engine, failingOnB)).isGreaterThanOrEqualTo(1);
            assertThat(EngineSeed.failingTimerCountFor(engine, retrying)).isGreaterThanOrEqualTo(1);
        });
        // The health strip reads the scheduled M1 probe's registry state — wait for one cycle.
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(rest.getForObject("/api/engines", JsonNode.class)
                                .get(0)
                                .get("reachable")
                                .asBoolean())
                        .isTrue());
    }

    @Test
    @Order(1)
    void aggregatesStatusCountsAndVersionSplitErrorGroupsFromTheLiveEngine() {
        JsonNode body = rest.getForObject("/api/triage", JsonNode.class);

        assertThat(body.get("asOf").asText()).isNotBlank();
        assertThat(body.get("perEngine").get("engine-a").get("ok").asBoolean()).isTrue();
        assertThat(body.get("perEngine").get("engine-a").get("dlqScan").asText())
                .isEqualTo("complete");

        // health strip (M1 probe data, zero extra engine calls)
        JsonNode strip = body.get("engines").get(0);
        assertThat(strip.get("id").asText()).isEqualTo("engine-a");
        assertThat(strip.get("reachable").asBoolean()).isTrue();
        assertThat(strip.get("jobLanes").get("deadletter").asLong()).isGreaterThanOrEqualTo(2);

        // status counts: all three tiers evidenced, global == the single engine's map
        JsonNode counts = body.get("statusCountsByEngine").get("engine-a");
        assertThat(counts.get("ACTIVE").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(counts.get("SUSPENDED").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(counts.get("COMPLETED").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(body.get("statusCounts")).isEqualTo(counts);

        // the arithmetic group: root-cause class via stacktrace refinement, counts split
        // by definition version, zero-filled with every deployed sibling version
        JsonNode group = arithmeticGroup(body);
        assertThat(group.get("algoVersion").asInt()).isEqualTo(1);
        assertThat(group.get("signatureHash").asText()).hasSize(64);
        assertThat(group.get("normalizedMessage").asText()).isEqualTo("/ by zero");
        assertThat(group.get("deadLetterCount").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(group.get("retryingCount").asLong()).isGreaterThanOrEqualTo(1);
        JsonNode versioned = group.get("countsByEngine").get("engine-a");
        assertThat(versioned.get("demoFailingPayment:v" + versionA).asLong()).isGreaterThanOrEqualTo(1);
        assertThat(versioned.get("demoFailingPayment:v" + versionB).asLong()).isGreaterThanOrEqualTo(1);
        for (int version : EngineSeed.deployedVersions(engine, "demoFailingPayment")) {
            assertThat(versioned.has("demoFailingPayment:v" + version))
                    .as("every deployed version is present — zero-filled when clean (v%s)", version)
                    .isTrue();
        }

        // zero unparseable groups (SPEC §10: a silent parser failure is the forbidden lie)
        body.get("errorGroups").forEach(g -> {
            assertThat(g.get("normalizedMessage").asText()).isNotBlank();
            assertThat(g.get("signatureHash").asText()).hasSize(64);
        });
    }

    @Test
    @Order(2)
    void statusCountsAreQueryTotalsNeverRowFetches() {
        // Aggregation-independence, proven on the wire: every status-count query the BFF
        // issued carried size:1 — totals only. (The journal holds all order-1 traffic.)
        List<String> queryBodies = proxy.getAllServeEvents().stream()
                .map(e -> e.getRequest())
                .filter(r -> r.getUrl().contains("/query/"))
                .map(r -> r.getBodyAsString())
                .toList();
        assertThat(queryBodies).isNotEmpty();
        assertThat(queryBodies).allSatisfy(bodyJson -> assertThat(bodyJson).contains("\"size\":1"));
    }

    @Test
    @Order(3)
    void callsInsideTheTtlHitTheBffCacheMakingZeroEngineCalls() {
        long before = triageEngineCalls();
        JsonNode first = rest.getForObject("/api/triage", JsonNode.class);
        JsonNode second = rest.getForObject("/api/triage", JsonNode.class);
        assertThat(second.get("asOf")).isEqualTo(first.get("asOf"));
        assertThat(triageEngineCalls())
                .as("a cache hit must not touch the engine (thundering-herd protection)")
                .isEqualTo(before);
    }

    @Test
    @Order(4)
    void refreshBypassesTheCacheOncePerThrottleInterval() {
        long before = triageEngineCalls();
        JsonNode refreshed = rest.getForObject("/api/triage?refresh=true", JsonNode.class);
        long afterBypass = triageEngineCalls();
        assertThat(afterBypass).as("refresh=true must re-query the engine").isGreaterThan(before);

        JsonNode throttled = rest.getForObject("/api/triage?refresh=true", JsonNode.class);
        assertThat(throttled.get("asOf")).isEqualTo(refreshed.get("asOf"));
        assertThat(triageEngineCalls())
                .as("a second bypass inside the 10s throttle serves the cached snapshot")
                .isEqualTo(afterBypass);
    }

    @Test
    @Order(5)
    void failedAndRetryingStatusCountsAreSynthesizedAndMathematicallyAccurate() {
        JsonNode body = rest.getForObject("/api/triage", JsonNode.class);
        JsonNode counts = body.get("statusCountsByEngine").get("engine-a");

        // The engine state is static since seeding (the retry fixture is pinned R10/PT1H),
        // so the BFF's synthesized counts must EQUAL the engine truth computed here from
        // the same lanes: FAILED = distinct instances holding dead-letter jobs, RETRYING =
        // distinct instances with withException jobs minus the FAILED set (precedence).
        java.util.Set<String> failedInstances = distinctInstancesOf("/management/deadletter-jobs?withException=true");
        java.util.Set<String> retryingInstances = distinctInstancesOf("/management/timer-jobs?withException=true");
        retryingInstances.addAll(distinctInstancesOf("/management/jobs?withException=true"));
        retryingInstances.removeAll(failedInstances);

        assertThat(counts.get("FAILED").asLong())
                .as("FAILED = distinct dead-lettered instances, exactly")
                .isEqualTo(failedInstances.size());
        assertThat(counts.get("RETRYING").asLong())
                .as("RETRYING = distinct failing-with-retries-left instances, FAILED wins collisions")
                .isEqualTo(retryingInstances.size());
        assertThat(counts.get("FAILED").asLong()).isGreaterThanOrEqualTo(2); // both seeded versions
        assertThat(counts.get("RETRYING").asLong()).isGreaterThanOrEqualTo(1); // the pinned fixture
        // The global map still mirrors the single engine's, new keys included.
        assertThat(body.get("statusCounts")).isEqualTo(counts);
    }

    /** Distinct BPMN process-instance ids across ALL pages of one failure lane (engine truth). */
    @SuppressWarnings("unchecked")
    private java.util.Set<String> distinctInstancesOf(String laneUrl) {
        java.util.Set<String> instances = new java.util.HashSet<>();
        int start = 0;
        while (true) {
            java.util.Map<String, Object> page = engine.get()
                    .uri(laneUrl + "&start=" + start + "&size=200")
                    .retrieve()
                    .body(java.util.Map.class);
            List<java.util.Map<String, Object>> data = (List<java.util.Map<String, Object>>) page.get("data");
            if (data == null || data.isEmpty()) break;
            for (java.util.Map<String, Object> job : data) {
                Object pid = job.get("processInstanceId");
                Object scopeType = job.get("scopeType");
                boolean bpmn = scopeType == null || "bpmn".equalsIgnoreCase(String.valueOf(scopeType));
                if (pid != null && !String.valueOf(pid).isBlank() && bpmn) {
                    instances.add(String.valueOf(pid));
                }
            }
            start += data.size();
            if (start >= ((Number) page.get("total")).longValue()) break;
        }
        return instances;
    }

    /** Engine calls only the triage aggregation makes (the M1 probe touches none of these). */
    private long triageEngineCalls() {
        return proxy.getAllServeEvents().stream()
                .map(e -> e.getRequest().getUrl())
                .filter(url -> url.contains("/query/")
                        || url.contains("withException=true")
                        || url.contains("/exception-stacktrace")
                        || url.contains("/repository/process-definitions"))
                .count();
    }

    private JsonNode arithmeticGroup(JsonNode body) {
        for (JsonNode group : body.get("errorGroups")) {
            if ("java.lang.ArithmeticException"
                    .equals(group.get("exceptionClass").asText(null))) {
                return group;
            }
        }
        throw new AssertionError("no java.lang.ArithmeticException group in: "
                + body.get("errorGroups").toPrettyString());
    }
}
