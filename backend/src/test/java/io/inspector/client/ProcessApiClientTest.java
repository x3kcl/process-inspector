package io.inspector.client;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.Timeouts;
import io.inspector.support.TestEngines;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Process-API-specific wire behavior: latest-version definition resolution, whole-second
 * dueBefore encoding on the overdue-timer query, and the size=1 lane-count totals trick.
 * Cross-cutting GuardedCaller concerns (auth/timeout/breaker/forwarded-user) live in
 * {@link GuardedCallerTest}. Never mocked against real Flowable join/paging semantics — those
 * are rung 4 (engine-harness iron rule).
 */
class ProcessApiClientTest {

    private WireMockServer wm;
    private MockEnvironment env;
    private CircuitBreakerRegistry breakers;
    private BulkheadRegistry bulkheads;
    private ProcessApiClient client;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        env = new MockEnvironment();
        env.setProperty("ENGINE_T_PASSWORD", "test");
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(500))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .ignoreExceptions(HttpClientErrorException.class)
                .build();
        breakers = CircuitBreakerRegistry.of(Map.of("engine", config));
        bulkheads = BulkheadRegistry.of(Map.of(
                "engine",
                BulkheadConfig.custom()
                        .maxConcurrentCalls(8)
                        .maxWaitDuration(Duration.ofSeconds(5))
                        .build()));
        client = new ProcessApiClient(new GuardedCaller(env, breakers, bulkheads));
    }

    @AfterEach
    void tearDown() {
        ForwardedActor.clear();
        wm.stop();
    }

    private EngineConfig engine(String id) {
        return TestEngines.engine(
                id,
                wm.baseUrl(),
                TestEngines.basicAuth("rest-admin", "ENGINE_T_PASSWORD"),
                new Timeouts(1000, 1000, null));
    }

    @Test
    void latestProcessDefinitionByKeySendsLatestTrue() {
        // A plain size=1 does NOT return the latest version (name-ascending default) — the
        // migration default-target resolution needs latest=true. Pin it on the wire.
        wm.stubFor(get(urlPathEqualTo("/repository/process-definitions"))
                .willReturn(
                        okJson("{\"data\":[{\"id\":\"demoMigration:5:abc\",\"key\":\"demoMigration\",\"version\":5}],"
                                + "\"total\":1,\"start\":0,\"size\":1}")));

        FlowablePage page =
                client.latestProcessDefinitionByKey(engine("latest-engine"), CallPriority.INTERACTIVE, "demoMigration");

        assertThat(page.dataOrEmpty().get(0)).containsEntry("version", 5);
        wm.verify(getRequestedFor(urlPathEqualTo("/repository/process-definitions"))
                .withQueryParam("key", equalTo("demoMigration"))
                .withQueryParam("latest", equalTo("true"))
                .withQueryParam("size", equalTo("1")));
    }

    @Test
    void overdueTimerQuerySendsWholeSecondDueBefore() {
        // Regression (found on the dockerized engine): Flowable 400s on fractional seconds.
        EngineConfig engine = engine("timer-engine");
        wm.stubFor(get(urlPathEqualTo("/management/timer-jobs"))
                .willReturn(okJson("{\"data\":[],\"total\":3,\"start\":0,\"size\":0}")));

        long total = client.countOverdueTimers(
                engine, CallPriority.INTERACTIVE, java.time.Instant.parse("2026-07-06T06:20:00.465Z"));

        assertThat(total).isEqualTo(3);
        wm.verify(getRequestedFor(urlPathEqualTo("/management/timer-jobs"))
                .withQueryParam("dueBefore", equalTo("2026-07-06T06:20:00Z")));
    }

    @Test
    void laneCountsUseSizeOneTotalsTrick() {
        EngineConfig engine = engine("lane-engine");
        wm.stubFor(get(urlPathEqualTo("/management/deadletter-jobs"))
                .willReturn(okJson("{\"data\":[{\"id\":\"j1\"}],\"total\":137,\"start\":0,\"size\":1}")));

        long total = client.countJobs(engine, CallPriority.INTERACTIVE, ProcessApiClient.JobLaneKind.DEADLETTER);

        assertThat(total).isEqualTo(137);
        wm.verify(getRequestedFor(urlPathEqualTo("/management/deadletter-jobs")).withQueryParam("size", equalTo("1")));
    }
}
