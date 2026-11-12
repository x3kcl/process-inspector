package io.inspector.triage;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.client.GuardedCaller;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.RecentEngineErrors;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.Timeouts;
import io.inspector.config.InspectorProperties.Triage;
import io.inspector.dto.ErrorGroup;
import io.inspector.dto.TriageDashboardResponse;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineHealth;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Rung 2 (#464): the Stage-0 aggregation over a REAL {@link ProcessApiClient} whose engine is a
 * WireMock serving RECORDED flowable-rest 6.8 / 7.1 answers ({@code src/test/resources/wire/}, seeded
 * over REST: two dead-lettered {@code demoFailingPayment} instances, one dead-lettered call-activity
 * child under {@code demoParent}, one {@code demoFailingRetry} instance parked RETRYING in the timer
 * table, a completed {@code demoOrder}, an open {@code demoUserTask}). Proves the totals-only status
 * counts, the FAILED/RETRYING synthesis, snippet grouping + stacktrace display refinement, the
 * cross-engine merge, truncation honesty and the sick-engine envelope at unit speed. The live join on
 * a moving engine stays in {@code TriageAggregationIT}.
 */
class TriageAggregationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T08:00:00Z"), ZoneOffset.UTC);

    private static final CircuitBreakerConfig BREAKER = CircuitBreakerConfig.custom()
            .recordExceptions(ResourceAccessException.class, HttpServerErrorException.class)
            .ignoreExceptions(HttpClientErrorException.class)
            .build();
    private static final BulkheadConfig BULKHEAD = BulkheadConfig.custom()
            .maxConcurrentCalls(16)
            .maxWaitDuration(Duration.ofSeconds(5))
            .build();

    private final List<WireMockServer> servers = new ArrayList<>();
    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final MockEnvironment env = new MockEnvironment().withProperty("ENGINE_T_PASSWORD", "test");
    private final ProcessApiClient client = new ProcessApiClient(new GuardedCaller(
            env,
            CircuitBreakerRegistry.of(Map.of("engine", BREAKER, "sampler", BREAKER)),
            BulkheadRegistry.of(Map.of("engine", BULKHEAD, "sampler", BULKHEAD)),
            new SimpleMeterRegistry(),
            new RecentEngineErrors(Clock.systemUTC())));

    @AfterEach
    void stopServers() {
        servers.forEach(WireMockServer::stop);
    }

    /* ------------------------------ fixtures ------------------------------ */

    private static String text(String major, String name) {
        try (InputStream in = TriageAggregationServiceTest.class.getResourceAsStream("/wire/" + major + "/" + name)) {
            if (in == null) throw new IllegalStateException("missing fixture wire/" + major + "/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String json(String major, String name) {
        return text(major, name + ".json");
    }

    /** A WireMock engine answering every Stage-0 leg with the recorded documents of one major. */
    private WireMockServer recordedEngine(String major) {
        WireMockServer wm = new WireMockServer(options().dynamicPort());
        wm.start();
        servers.add(wm);
        wm.stubFor(post(urlPathEqualTo("/query/process-instances"))
                .withRequestBody(equalToJson("{\"suspended\":false}", true, true))
                .willReturn(okJson(json(major, "query-process-instances"))));
        wm.stubFor(post(urlPathEqualTo("/query/process-instances"))
                .withRequestBody(equalToJson("{\"suspended\":true}", true, true))
                .willReturn(okJson(json(major, "query-process-instances-suspended"))));
        wm.stubFor(post(urlPathEqualTo("/query/historic-process-instances"))
                .willReturn(okJson(json(major, "query-historic-process-instances-finished"))));
        wm.stubFor(
                get(urlPathEqualTo("/management/deadletter-jobs")).willReturn(okJson(json(major, "deadletter-jobs"))));
        wm.stubFor(get(urlPathEqualTo("/management/timer-jobs")).willReturn(okJson(json(major, "timer-jobs"))));
        wm.stubFor(get(urlPathEqualTo("/management/jobs")).willReturn(okJson(json(major, "jobs"))));
        wm.stubFor(get(urlPathMatching("/management/deadletter-jobs/[^/]+/exception-stacktrace"))
                .willReturn(ok(text(major, "deadletter-exception-stacktrace.txt"))
                        .withHeader("Content-Type", "application/octet-stream")));
        wm.stubFor(get(urlPathMatching("/management/timer-jobs/[^/]+/exception-stacktrace"))
                .willReturn(ok(text(major, "timer-exception-stacktrace.txt"))
                        .withHeader("Content-Type", "application/octet-stream")));
        wm.stubFor(get(urlPathEqualTo("/repository/process-definitions"))
                .withQueryParam("key", equalTo("demoFailingPayment"))
                .willReturn(okJson(json(major, "process-definitions-by-key"))));
        wm.stubFor(get(urlPathEqualTo("/repository/process-definitions"))
                .withQueryParam("key", equalTo("demoFailingRetry"))
                .willReturn(okJson(json(major, "process-definitions-by-key-retry"))));
        return wm;
    }

    private EngineConfig engine(String id, WireMockServer wm, Integer dlqScanCap, String tenantId) {
        return TestEngines.builder(id, wm.baseUrl())
                .auth(TestEngines.basicAuth("rest-admin", "ENGINE_T_PASSWORD"))
                .timeouts(new Timeouts(2000, 2000, null))
                .dlqScanCap(dlqScanCap)
                .tenantId(tenantId)
                .build();
    }

    private void fleet(boolean scopeTypeCapable, EngineConfig... engines) {
        when(registry.all()).thenReturn(List.of(engines));
        when(registry.healthOf(anyString()))
                .thenReturn(new EngineHealth(
                        true,
                        "6.8.0",
                        null,
                        0,
                        new EngineCapabilities(true, true, scopeTypeCapable, scopeTypeCapable, true),
                        null,
                        null,
                        null));
    }

    private TriageAggregationService service(Integer stacktraceSampleCap) {
        InspectorProperties props =
                new InspectorProperties(4, 10, new Triage(null, null, stacktraceSampleCap, null), null, List.of());
        return new TriageAggregationService(registry, client, props, CLOCK);
    }

    /* ------------------------------ tests ------------------------------ */

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void recordedEngineYieldsTotalsOnlyCountsAndSynthesizedFailureTiers(String major) {
        WireMockServer wm = recordedEngine(major);
        fleet(true, engine("eng", wm, null, null));

        TriageDashboardResponse res = service(null).aggregate();

        assertThat(res.asOf()).isEqualTo("2026-11-12T08:00:00Z");
        // ACTIVE/SUSPENDED/COMPLETED are the recorded query TOTALS; FAILED = distinct instances
        // holding a dead-letter job (two payments + the call-activity child), RETRYING = the
        // instance whose withException job is parked in the timer table.
        assertThat(res.statusCounts())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("ACTIVE", 6L, "SUSPENDED", 0L, "COMPLETED", 1L, "FAILED", 3L, "RETRYING", 1L));
        assertThat(res.statusCountsByEngine().get("eng")).isEqualTo(res.statusCounts());
        TriageDashboardResponse.PerEngineTriage env = res.perEngine().get("eng");
        assertThat(env.ok()).isTrue();
        assertThat(env.dlqScan()).isEqualTo("complete");
        assertThat(env.outOfScopeDeadletters()).isZero(); // every recorded DLQ row is BPMN-scoped
        assertThat(env.deadletterTruncated()).isFalse();
        assertThat(res.engines())
                .singleElement()
                .satisfies(e -> assertThat(e.id()).isEqualTo("eng"));

        // Every failing job is the same ${amount % divisor} expression failure: the groups (one, or
        // one per definition where the engine embeds it in the message) together hold all 4 jobs,
        // and the stacktrace sample refines each group's display class to the root cause.
        assertThat(res.errorGroups()).isNotEmpty();
        assertThat(res.errorGroups().stream().mapToLong(ErrorGroup::total).sum())
                .isEqualTo(4);
        assertThat(res.errorGroups().stream()
                        .mapToLong(ErrorGroup::deadLetterCount)
                        .sum())
                .isEqualTo(3);
        assertThat(res.errorGroups().stream()
                        .mapToLong(ErrorGroup::retryingCount)
                        .sum())
                .isEqualTo(1);
        assertThat(res.errorGroups()).allSatisfy(g -> {
            assertThat(g.exceptionClass()).isEqualTo("java.lang.ArithmeticException");
            assertThat(g.sampleRawMessage()).contains("${amount % divisor}");
        });
        assertThat(res.errorGroups())
                .isSortedAccordingTo(
                        java.util.Comparator.comparingLong(ErrorGroup::total).reversed());
        Map<String, Long> versions = new java.util.TreeMap<>();
        res.errorGroups()
                .forEach(g -> g.countsByEngine().get("eng").forEach((k, n) -> versions.merge(k, n, Long::sum)));
        assertThat(versions)
                .containsExactlyInAnyOrderEntriesOf(Map.of("demoFailingPayment:v1", 3L, "demoFailingRetry:v1", 1L));
    }

    @Test
    void twoEnginesMergeCountsAndASickEngineDegradesToItsEnvelope() {
        WireMockServer six = recordedEngine("6.8");
        WireMockServer seven = recordedEngine("7.1");
        WireMockServer sick = new WireMockServer(options().dynamicPort());
        sick.start();
        servers.add(sick);
        sick.stubFor(com.github.tomakehurst.wiremock.client.WireMock.any(anyUrl())
                .willReturn(aResponse().withStatus(500)));
        fleet(
                true,
                engine("six", six, null, null),
                engine("seven", seven, null, null),
                engine("sick", sick, null, null));

        TriageDashboardResponse res = service(null).aggregate(CallPriority.BACKGROUND);

        assertThat(res.statusCounts()).containsEntry("ACTIVE", 12L).containsEntry("FAILED", 6L);
        assertThat(res.statusCountsByEngine()).containsOnlyKeys("six", "seven");
        assertThat(res.perEngine().get("sick").ok()).isFalse();
        assertThat(res.perEngine().get("sick").error()).isNotBlank();
        assertThat(res.engines()).hasSize(3); // the sick engine still shows on the health strip
        assertThat(res.errorGroups().stream().mapToLong(ErrorGroup::total).sum())
                .isEqualTo(8);
        assertThat(res.errorGroups())
                .allSatisfy(g -> assertThat(g.countsByEngine()).doesNotContainKey("sick"));
    }

    @Test
    void aCappedDeadLetterScanIsLabelledTruncatedAndItsCountsAreLowerBounds() {
        WireMockServer wm = recordedEngine("6.8");
        // The recorded size=2 page: 2 rows of a total of 3 — the scan stops at the cap.
        wm.stubFor(get(urlPathEqualTo("/management/deadletter-jobs"))
                .withQueryParam("start", equalTo("0"))
                .willReturn(okJson(json("6.8", "deadletter-jobs-first-2"))));
        fleet(true, engine("eng", wm, 2, null));

        TriageDashboardResponse res = service(null).aggregate();

        TriageDashboardResponse.PerEngineTriage env = res.perEngine().get("eng");
        assertThat(env.dlqScan()).startsWith("truncated@");
        assertThat(env.deadletterTruncated()).isTrue();
        assertThat(res.statusCounts()).containsEntry("FAILED", 2L);
        wm.verify(getRequestedFor(urlPathEqualTo("/management/deadletter-jobs")).withQueryParam("size", equalTo("2")));
    }

    @Test
    void scopeBlindEngineReportsUnknownOutOfScopeAndTenantIsThreadedOnEveryLeg() {
        WireMockServer wm = recordedEngine("6.8");
        fleet(false, engine("eng", wm, null, "acme"));

        TriageDashboardResponse res = service(null).aggregate();

        assertThat(res.perEngine().get("eng").outOfScopeDeadletters()).isNull(); // unknown, not zero
        wm.verify(getRequestedFor(urlPathEqualTo("/management/deadletter-jobs"))
                .withQueryParam("tenantId", equalTo("acme"))
                .withQueryParam("withException", equalTo("true")));
        wm.verify(
                2,
                postRequestedFor(urlPathEqualTo("/query/process-instances"))
                        .withRequestBody(equalToJson("{\"tenantId\":\"acme\",\"size\":1}", true, true)));
        wm.verify(postRequestedFor(urlPathEqualTo("/query/historic-process-instances"))
                .withRequestBody(equalToJson("{\"finished\":true,\"tenantId\":\"acme\"}", true, true)));
    }

    @Test
    void refinementIsDisplayOnlyAMissedOrDisabledStacktraceKeepsTheSameGroupIdentity() {
        WireMockServer refined = recordedEngine("6.8");
        fleet(true, engine("eng", refined, null, null));
        List<ErrorGroup> withRefinement = service(null).aggregate().errorGroups();

        // Budget 0: no stacktrace is ever fetched.
        refined.resetRequests();
        List<ErrorGroup> noBudget = service(0).aggregate().errorGroups();
        refined.verify(0, getRequestedFor(urlPathMatching(".*/exception-stacktrace")));

        // A failing stacktrace fetch degrades to the snippet class, never a failed engine.
        refined.stubFor(get(urlPathMatching(".*/exception-stacktrace")).willReturn(notFound()));
        refined.stubFor(get(urlPathEqualTo("/repository/process-definitions"))
                .willReturn(aResponse().withStatus(500)));
        TriageDashboardResponse missed = service(null).aggregate();

        assertThat(missed.perEngine().get("eng").ok()).isTrue();
        // #270: identity (hash + normalized message) never depends on whether refinement ran.
        assertThat(noBudget)
                .extracting(ErrorGroup::signatureHash)
                .containsExactlyElementsOf(
                        withRefinement.stream().map(ErrorGroup::signatureHash).toList());
        assertThat(missed.errorGroups())
                .extracting(ErrorGroup::signatureHash)
                .containsExactlyElementsOf(
                        withRefinement.stream().map(ErrorGroup::signatureHash).toList());
        assertThat(withRefinement)
                .allSatisfy(g -> assertThat(g.exceptionClass()).isEqualTo("java.lang.ArithmeticException"));
        assertThat(noBudget)
                .allSatisfy(g -> assertThat(g.exceptionClass()).isNotEqualTo("java.lang.ArithmeticException"));
    }
}
