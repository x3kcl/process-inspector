package io.inspector.heat;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Ticker;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.action.GuardRefusedException;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.GuardedCaller;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.RecentEngineErrors;
import io.inspector.config.HeatProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.Timeouts;
import io.inspector.dto.DefinitionHeat;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineHealth;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * #784 rung 2 (stubbed HTTP): the heat gather's request shapes against a WireMock engine, through
 * the real {@link GuardedCaller}. DEFINITION-HEATMAP.md §1.2/§3: one sample query of finished
 * instances (newest end first; its {@code total} is the "of M" — no second count, no scan of the
 * activity table), then ONE activity page per sampled instance by {@code processInstanceId} (never
 * the definition-wide activity query, never {@code startedAfter} / {@code processInstanceIds}: the
 * engines silently ignore both), then the version's BPMN once. Every call runs on the AGGREGATE
 * lane ({@code "{id}:aggregate"}), asserted through the same chokepoint metric the GuardedCaller
 * lane tests read.
 */
class DefinitionHeatGatherTest {

    private static final String P = "/flowable-rest/service";
    private static final String ACTIVITIES = P + "/history/historic-activity-instances";
    private static final String SAMPLE = P + "/query/historic-process-instances";
    private static final String DEF = P + "/repository/process-definitions/d1";
    private static final String BPMN = DEF + "/resourcedata";

    private WireMockServer wm;
    private BulkheadRegistry bulkheads;
    private CircuitBreakerRegistry breakers;
    private SimpleMeterRegistry metrics;
    private EngineRegistry registry;
    private GuardedCaller guarded;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("ENGINE_T_PASSWORD", "test");
        CircuitBreakerConfig breaker = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(50)
                .minimumNumberOfCalls(50)
                .recordExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .ignoreExceptions(HttpClientErrorException.class)
                .build();
        breakers = CircuitBreakerRegistry.of(Map.of("engine", breaker, "aggregate", breaker));
        BulkheadConfig lane = BulkheadConfig.custom()
                .maxConcurrentCalls(2)
                .maxWaitDuration(Duration.ofSeconds(5))
                .build();
        bulkheads = BulkheadRegistry.of(Map.of("engine", lane, "aggregate", lane));
        metrics = new SimpleMeterRegistry();
        guarded = new GuardedCaller(env, breakers, bulkheads, metrics, new RecentEngineErrors(Clock.systemUTC()));
        registry = mock(EngineRegistry.class);
        when(registry.resolveOrNotFound("t")).thenReturn(engine(200));
        when(registry.healthOf("t")).thenReturn(EngineHealth.unknown());
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private EngineConfig engine(int maxPageSize) {
        return TestEngines.builder("t", wm.baseUrl() + P)
                .auth(TestEngines.basicAuth("rest-admin", "ENGINE_T_PASSWORD"))
                .timeouts(new Timeouts(1000, 1000, null))
                .maxPageSize(maxPageSize)
                .build();
    }

    private DefinitionHeatService service(HeatProperties props) {
        return new DefinitionHeatService(
                registry,
                new ProcessApiClient(guarded),
                new CmmnApiClient(guarded),
                props,
                Clock.fixed(Instant.parse("2026-11-18T08:00:00Z"), ZoneOffset.UTC),
                Ticker.systemTicker());
    }

    private DefinitionHeat heat(Integer n) {
        return service(new HeatProperties(null, null)).heat("t", "d1", n, false);
    }

    // ---- stubs ------------------------------------------------------------------------------

    private void definitionExists() {
        wm.stubFor(get(urlPathEqualTo(DEF)).willReturn(okJson("{\"id\":\"d1\",\"key\":\"heatSeed\",\"version\":1}")));
    }

    private static String instance(String id, int endSecond) {
        return "{\"id\":\"%s\",\"processDefinitionId\":\"d1\",\"startTime\":\"2026-11-17T10:00:00.000+00:00\","
                        .formatted(id)
                + "\"endTime\":\"2026-11-17T10:00:%02d.000+00:00\"}".formatted(endSecond);
    }

    private static String page(long total, String... rows) {
        return "{\"data\":[" + String.join(",", rows) + "],\"total\":" + total + ",\"start\":0,\"size\":" + rows.length
                + "}";
    }

    private void sample(long total, String... ids) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            rows.add(instance(ids[i], 50 - i));
        }
        wm.stubFor(post(urlPathEqualTo(SAMPLE)).willReturn(okJson(page(total, rows.toArray(String[]::new)))));
    }

    private static String row(String id, String pid, String activityId, String type, int startSecond) {
        return ("{\"id\":\"%s\",\"activityId\":\"%s\",\"activityName\":\"%s\",\"activityType\":\"%s\","
                        + "\"processInstanceId\":\"%s\",\"processDefinitionId\":\"d1\","
                        + "\"startTime\":\"2026-11-17T10:00:%02d.000+00:00\",\"endTime\":\"2026-11-17T10:00:%02d.000+00:00\","
                        + "\"durationInMillis\":1000}")
                .formatted(id, activityId, activityId, type, pid, startSecond, startSecond + 1);
    }

    private void activities(String pid, long total) {
        wm.stubFor(get(urlPathEqualTo(ACTIVITIES))
                .withQueryParam("processInstanceId", equalTo(pid))
                .willReturn(okJson(page(
                        total,
                        row(pid + "-1", pid, "start", "startEvent", 0),
                        row(pid + "-2", pid, "check", "manualTask", 1),
                        row(pid + "-3", pid, "approved", "endEvent", 2)))));
    }

    private void bpmn(String xml) {
        wm.stubFor(
                get(urlPathEqualTo(BPMN)).willReturn(aResponse().withStatus(200).withBody(xml)));
    }

    private static String evgwXml() throws IOException {
        try (InputStream in = DefinitionHeatGatherTest.class.getResourceAsStream("/heat/heatEvgw.bpmn20.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---- request shapes ---------------------------------------------------------------------

    @Test
    void oneSampleQueryThenOneActivityPagePerInstanceThenTheBpmnOnce() throws IOException {
        definitionExists();
        sample(10, "p1", "p2");
        activities("p1", 3);
        activities("p2", 3);
        bpmn(evgwXml());

        DefinitionHeat heat = heat(null);

        wm.verify(
                1,
                postRequestedFor(urlPathEqualTo(SAMPLE))
                        .withRequestBody(
                                equalToJson("{\"processDefinitionId\":\"d1\",\"finished\":true,\"sort\":\"endTime\","
                                        + "\"order\":\"desc\",\"start\":0,\"size\":200}")));
        wm.verify(2, getRequestedFor(urlPathEqualTo(ACTIVITIES)));
        for (String pid : List.of("p1", "p2")) {
            wm.verify(
                    1,
                    getRequestedFor(urlPathEqualTo(ACTIVITIES))
                            .withQueryParam("processInstanceId", equalTo(pid))
                            .withQueryParam("size", equalTo("200"))
                            .withQueryParam("startedAfter", absent())
                            .withQueryParam("processInstanceIds", absent())
                            .withQueryParam("processDefinitionId", absent()));
        }
        wm.verify(1, getRequestedFor(urlPathEqualTo(BPMN)));
        wm.verify(0, getRequestedFor(urlPathEqualTo(P + "/history/historic-process-instances")));
        assertThat(heat.finishedTotal()).isEqualTo(10);
        assertThat(heat.sampledInstances()).isEqualTo(2);
        assertThat(heat.requestedN()).isEqualTo(200);
        assertThat(heat.structure()).isEqualTo(DefinitionHeat.Structure.RESOLVED);
        assertThat(heat.asOf()).isEqualTo(Instant.parse("2026-11-18T08:00:00Z"));
        assertThat(heat.newestEnd()).isEqualTo(Instant.parse("2026-11-17T10:00:50Z"));
        assertThat(heat.oldestEnd()).isEqualTo(Instant.parse("2026-11-17T10:00:49Z"));
        assertThat(heat.nodes()).extracting(DefinitionHeat.NodeHeat::activityId).contains("check");
        assertThat(heat.partial()).isFalse();
    }

    @Test
    void everyEngineCallOfTheGatherRunsOnTheAggregateLane() {
        definitionExists();
        sample(2, "p1", "p2");
        activities("p1", 3);
        activities("p2", 3);
        bpmn("<definitions/>");

        heat(null);

        List<Timer> timers = new ArrayList<>(metrics.find("engine_fanout_duration_seconds")
                .tag("engineId", "t")
                .timers());
        assertThat(timers).extracting(t -> t.getId().getTag("leg")).containsExactly("AGGREGATE");
        assertThat(timers.get(0).count()).isEqualTo(wm.getAllServeEvents().size());
        assertThat(bulkheads.getAllBulkheads().stream().map(Bulkhead::getName).collect(Collectors.toSet()))
                .containsExactly("t:aggregate");
        assertThat(breakers.getAllCircuitBreakers().stream()
                        .map(CircuitBreaker::getName)
                        .collect(Collectors.toSet()))
                .containsExactly("t:aggregate");
    }

    // ---- the clamp ------------------------------------------------------------------------

    /** Five finished instances served in pages of two (engine max-page-size 2), whatever is asked. */
    private void fivePagedInstances() {
        definitionExists();
        String[][] pages = {{"p1", "p2"}, {"p3", "p4"}, {"p5"}};
        int end = 50;
        for (int i = 0; i < pages.length; i++) {
            List<String> rows = new ArrayList<>();
            for (String id : pages[i]) {
                rows.add(instance(id, end--));
                activities(id, 3);
            }
            wm.stubFor(post(urlPathEqualTo(SAMPLE))
                    .withRequestBody(matchingJsonPath("$.start", equalTo(String.valueOf(i * 2))))
                    .willReturn(okJson(page(5, rows.toArray(String[]::new)))));
        }
        bpmn("<definitions/>");
        when(registry.resolveOrNotFound("t")).thenReturn(engine(2));
    }

    private DefinitionHeat clamped(Integer n) {
        fivePagedInstances();
        return service(new HeatProperties(3, 4)).heat("t", "d1", n, false);
    }

    @Test
    void belowTheClampTheRequestedSizeIsKept() {
        DefinitionHeat heat = clamped(3);

        assertThat(heat.requestedN()).isEqualTo(3);
        assertThat(heat.sampledInstances()).isEqualTo(3);
        wm.verify(postRequestedFor(urlPathEqualTo(SAMPLE))
                .withRequestBody(equalToJson("{\"start\":0,\"size\":2}", true, true)));
        wm.verify(postRequestedFor(urlPathEqualTo(SAMPLE))
                .withRequestBody(equalToJson("{\"start\":2,\"size\":1}", true, true)));
    }

    @Test
    void atTheClampTheRequestedSizeIsKept() {
        DefinitionHeat heat = clamped(4);

        assertThat(heat.requestedN()).isEqualTo(4);
        assertThat(heat.sampledInstances()).isEqualTo(4);
        wm.verify(2, postRequestedFor(urlPathEqualTo(SAMPLE)));
    }

    @Test
    void aboveTheClampTheSizeIsClamped() {
        DefinitionHeat heat = clamped(5);

        assertThat(heat.requestedN()).isEqualTo(4);
        assertThat(heat.sampledInstances()).isEqualTo(4);
        wm.verify(
                0, postRequestedFor(urlPathEqualTo(SAMPLE)).withRequestBody(matchingJsonPath("$.start", equalTo("4"))));
        wm.verify(4, getRequestedFor(urlPathEqualTo(ACTIVITIES)));
    }

    @Test
    void noRequestedSizeTakesTheConfiguredDefault() {
        assertThat(clamped(null).requestedN()).isEqualTo(3);
    }

    @Test
    void aSizeBelowOneIsRaisedToOne() {
        DefinitionHeat heat = clamped(0);

        assertThat(heat.requestedN()).isEqualTo(1);
        assertThat(heat.sampledInstances()).isEqualTo(1);
    }

    // ---- drop rules and the BPMN read -------------------------------------------------------

    @Test
    void anInstanceWithMoreRowsThanOnePageIsDroppedAndCounted() {
        definitionExists();
        sample(2, "p1", "p2");
        activities("p1", 3);
        activities("p2", 201);
        bpmn("<definitions/>");

        DefinitionHeat heat = heat(null);

        assertThat(heat.dropReasons().tooManyRows()).isEqualTo(1);
        assertThat(heat.dropReasons().readFailed()).isZero();
        assertThat(heat.droppedInstances()).isEqualTo(1);
        assertThat(heat.partial()).isTrue();
        wm.verify(1, getRequestedFor(urlPathEqualTo(ACTIVITIES)).withQueryParam("processInstanceId", equalTo("p2")));
    }

    @Test
    void anInstanceWhoseReadFailsIsDroppedAndCounted() {
        definitionExists();
        sample(2, "p1", "p2");
        activities("p1", 3);
        wm.stubFor(get(urlPathEqualTo(ACTIVITIES))
                .withQueryParam("processInstanceId", equalTo("p2"))
                .willReturn(aResponse().withStatus(500)));
        bpmn("<definitions/>");

        DefinitionHeat heat = heat(null);

        assertThat(heat.dropReasons().readFailed()).isEqualTo(1);
        assertThat(heat.dropReasons().tooManyRows()).isZero();
        assertThat(heat.partial()).isTrue();
        assertThat(heat.nodes()).isNotEmpty();
        // no retry of a failed read beyond what the read lanes do
        wm.verify(1, getRequestedFor(urlPathEqualTo(ACTIVITIES)).withQueryParam("processInstanceId", equalTo("p2")));
    }

    @Test
    void aFailedBpmnReadDoesNotFailTheGatherAndSaysStructureUnavailable() {
        definitionExists();
        sample(1, "p1");
        activities("p1", 3);
        wm.stubFor(get(urlPathEqualTo(BPMN)).willReturn(aResponse().withStatus(500)));

        DefinitionHeat heat = heat(null);

        assertThat(heat.structure()).isEqualTo(DefinitionHeat.Structure.UNAVAILABLE);
        assertThat(heat.coverage()).isEqualTo(DefinitionHeat.Coverage.NO_FLOW_HISTORY);
        assertThat(heat.nodes()).extracting(DefinitionHeat.NodeHeat::activityId).contains("check");
    }

    @Test
    void everyActivityReadFailingIsAnErrorNotAnEmptyMap() {
        definitionExists();
        sample(1, "p1");
        wm.stubFor(get(urlPathEqualTo(ACTIVITIES)).willReturn(aResponse().withStatus(500)));
        bpmn("<definitions/>");

        assertThatThrownBy(() -> heat(null)).isInstanceOfSatisfying(GuardRefusedException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.code()).isEqualTo("heat-unavailable");
        });
    }

    @Test
    void aFailedSampleQueryIsAServiceUnavailableRefusal() {
        definitionExists();
        wm.stubFor(post(urlPathEqualTo(SAMPLE)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> heat(null)).isInstanceOfSatisfying(GuardRefusedException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.code()).isEqualTo("engine-unavailable");
        });
    }

    @Test
    void noFinishedInstanceIsNoHistoryWithoutActivityReads() {
        definitionExists();
        sample(0);

        DefinitionHeat heat = heat(null);

        assertThat(heat.coverage()).isEqualTo(DefinitionHeat.Coverage.NO_HISTORY);
        assertThat(heat.finishedTotal()).isZero();
        wm.verify(0, getRequestedFor(urlPathEqualTo(ACTIVITIES)));
    }

    // ---- the definition must be a BPMN definition of that engine ----------------------------

    @Test
    void anUnknownDefinitionIsNotFoundAndNothingIsSampled() {
        wm.stubFor(get(urlPathEqualTo(DEF)).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> heat(null)).isInstanceOfSatisfying(GuardRefusedException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(e.code()).isEqualTo("definition-not-found");
        });
        wm.verify(0, postRequestedFor(urlPathEqualTo(SAMPLE)));
    }

    @Test
    void aCmmnCaseDefinitionIsRefusedAsOutOfScope() {
        wm.stubFor(get(urlPathEqualTo(DEF)).willReturn(aResponse().withStatus(404)));
        wm.stubFor(get(urlPathMatching(".*/cmmn-repository/case-definitions/d1"))
                .willReturn(okJson("{\"id\":\"d1\",\"key\":\"case\",\"version\":1}")));
        EngineHealth cmmn = mock(EngineHealth.class);
        when(cmmn.capabilities()).thenReturn(EngineCapabilities.fromVersion("6.8.0", true));
        when(registry.healthOf("t")).thenReturn(cmmn);

        assertThatThrownBy(() -> heat(null)).isInstanceOfSatisfying(GuardRefusedException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.code()).isEqualTo("heat-bpmn-only");
        });
        wm.verify(0, postRequestedFor(urlPathEqualTo(SAMPLE)));
    }
}
