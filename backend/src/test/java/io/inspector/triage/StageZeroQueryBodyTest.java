package io.inspector.triage;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.client.GuardedCaller;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.RecentEngineErrors;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.Timeouts;
import io.inspector.config.InspectorProperties.Triage;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineHealth;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import io.inspector.support.WireJson;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Characterization of the STAGE-0 query bodies (#720), read off the wire of a WireMock engine as
 * canonical JSON ({@link WireJson}): the triage status-count probes and the leak-view counts. It
 * also states the iron rule these bodies carry: a Stage-0 aggregation asks the instance queries
 * for a TOTAL — {@code size=1}, one predicate, the tenant — and never sends the grid-search plan
 * (no sort, no paging offset, no variable filter, no id set); the dead-letter lane it groups is
 * read with explicit {@code start} and {@code size}, never as one unpaged fetch.
 */
class StageZeroQueryBodyTest {

    private static final String HISTORIC = "/query/historic-process-instances";
    private static final String RUNTIME = "/query/process-instances";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T08:00:00Z"), ZoneOffset.UTC);

    private final EngineRegistry registry = mock(EngineRegistry.class);
    private WireMockServer wm;
    private ProcessApiClient client;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        wm.stubFor(any(anyUrl()).atPriority(10).willReturn(okJson("{\"data\":[],\"total\":0,\"start\":0,\"size\":0}")));
        client = new ProcessApiClient(new GuardedCaller(
                new MockEnvironment().withProperty("ENGINE_T_PASSWORD", "test"),
                CircuitBreakerRegistry.of(Map.of(
                        "engine", CircuitBreakerConfig.ofDefaults(), "sampler", CircuitBreakerConfig.ofDefaults())),
                BulkheadRegistry.of(
                        Map.of("engine", BulkheadConfig.ofDefaults(), "sampler", BulkheadConfig.ofDefaults())),
                new SimpleMeterRegistry(),
                new RecentEngineErrors(Clock.systemUTC())));
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void theTriageStatusCountsAreThreeSizeOneProbesAndNothingOfTheGridPlan() {
        triage(engine(null)).aggregate();

        assertThat(bodies(RUNTIME))
                .containsExactlyInAnyOrder("{\"size\":1,\"suspended\":false}", "{\"size\":1,\"suspended\":true}");
        assertThat(bodies(HISTORIC)).containsExactly("{\"finished\":true,\"size\":1}");
    }

    @Test
    void aTenantPinnedEngineGetsTheTenantOnEveryTriageProbe() {
        triage(engine("acme")).aggregate();

        assertThat(bodies(RUNTIME))
                .containsExactlyInAnyOrder(
                        "{\"size\":1,\"suspended\":false,\"tenantId\":\"acme\"}",
                        "{\"size\":1,\"suspended\":true,\"tenantId\":\"acme\"}");
        assertThat(bodies(HISTORIC)).containsExactly("{\"finished\":true,\"size\":1,\"tenantId\":\"acme\"}");
    }

    @Test
    void theTriageDeadLetterLaneIsPagedWithStartAndSizeUntilItsTotalIsReached() {
        wm.stubFor(get(urlPathEqualTo("/management/deadletter-jobs"))
                .willReturn(okJson("{\"data\":[{\"id\":\"job-1\",\"processInstanceId\":\"pi-1\","
                        + "\"processDefinitionId\":\"demoOrder:3:abc\",\"exceptionMessage\":\"boom\"}],"
                        + "\"total\":450,\"start\":0,\"size\":1}")));

        triage(engine(null)).aggregate();

        assertThat(urls("/management/deadletter-jobs"))
                .containsExactly(
                        "/management/deadletter-jobs?start=0&size=200&withException=true",
                        "/management/deadletter-jobs?start=200&size=200&withException=true",
                        "/management/deadletter-jobs?start=400&size=200&withException=true");
    }

    @Test
    void theLeakViewCountsAreSizeOneRuntimeProbesPerWindowThenPerDefinitionKey() {
        wm.stubFor(post(urlPathEqualTo(RUNTIME))
                .willReturn(okJson("{\"data\":[{\"id\":\"pi-1\"}],\"total\":4,\"start\":0,\"size\":1}")));
        wm.stubFor(get(urlPathEqualTo("/repository/process-definitions"))
                .willReturn(okJson("{\"data\":[{\"id\":\"demoOrder:3:abc\",\"key\":\"demoOrder\",\"version\":3}],"
                        + "\"total\":1,\"start\":0,\"size\":1}")));
        EngineConfig engine = engine("acme");

        new LeakViewService(() -> List.of(engine), client, CLOCK, Duration.ofSeconds(20)).aggregate();

        String a30 = "\"startedBefore\":\"2026-10-18T08:00:00Z\"";
        String a90 = "\"startedBefore\":\"2026-08-19T08:00:00Z\"";
        String s7 = "\"startedBefore\":\"2026-11-10T08:00:00Z\"";
        assertThat(bodies(RUNTIME))
                .containsExactlyInAnyOrder(
                        "{\"size\":1," + a30 + ",\"suspended\":false,\"tenantId\":\"acme\"}",
                        "{\"size\":1," + a90 + ",\"suspended\":false,\"tenantId\":\"acme\"}",
                        "{\"size\":1," + s7 + ",\"suspended\":true,\"tenantId\":\"acme\"}",
                        "{\"processDefinitionKey\":\"demoOrder\",\"size\":1," + a30
                                + ",\"suspended\":false,\"tenantId\":\"acme\"}",
                        "{\"processDefinitionKey\":\"demoOrder\",\"size\":1," + a90
                                + ",\"suspended\":false,\"tenantId\":\"acme\"}",
                        "{\"processDefinitionKey\":\"demoOrder\",\"size\":1," + s7
                                + ",\"suspended\":true,\"tenantId\":\"acme\"}");
        assertThat(bodies(HISTORIC)).isEmpty();
    }

    /* ------------------------------ fixtures ------------------------------ */

    private EngineConfig engine(String tenantId) {
        return TestEngines.builder("eng", wm.baseUrl())
                .auth(TestEngines.basicAuth("rest-admin", "ENGINE_T_PASSWORD"))
                .timeouts(new Timeouts(2000, 2000, null))
                .tenantId(tenantId)
                .build();
    }

    private TriageAggregationService triage(EngineConfig engine) {
        when(registry.all()).thenReturn(List.of(engine));
        when(registry.healthOf(anyString()))
                .thenReturn(new EngineHealth(
                        true,
                        "6.8.0",
                        null,
                        0,
                        new EngineCapabilities(true, true, true, true, true),
                        null,
                        null,
                        null));
        InspectorProperties props = new InspectorProperties(4, 10, new Triage(null, null, null, null), null, List.of());
        return new TriageAggregationService(registry, client, props, CLOCK);
    }

    private List<String> bodies(String path) {
        return requestsTo(path).stream()
                .map(r -> WireJson.canonical(r.getBodyAsString()))
                .toList();
    }

    private List<String> urls(String path) {
        return requestsTo(path).stream().map(LoggedRequest::getUrl).toList();
    }

    /** Requests under {@code path}, oldest first (WireMock lists the newest first). */
    private List<LoggedRequest> requestsTo(String path) {
        List<ServeEvent> newestFirst = wm.getAllServeEvents();
        return newestFirst.reversed().stream()
                .map(ServeEvent::getRequest)
                .filter(r -> r.getUrl().equals(path) || r.getUrl().startsWith(path + "?"))
                .toList();
    }
}
