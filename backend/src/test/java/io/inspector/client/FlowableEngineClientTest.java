package io.inspector.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.Timeouts;
import io.inspector.support.TestEngines;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Rung 2: pure HTTP-client behavior against WireMock — auth header shape, timeouts,
 * redirect policy and the per-engine circuit breaker. Join/paging semantics are rung 4
 * (never mocked — engine-harness iron rule).
 */
class FlowableEngineClientTest {

    private WireMockServer wm;
    private MockEnvironment env;
    private CircuitBreakerRegistry breakers;
    private BulkheadRegistry bulkheads;
    private FlowableEngineClient client;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        env = new MockEnvironment();
        env.setProperty("ENGINE_T_PASSWORD", "test");
        // Mirrors the application-test.yml "engine" breaker config, built without Spring.
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
        client = new FlowableEngineClient(env, breakers, bulkheads);
    }

    @AfterEach
    void tearDown() {
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
    void emitsBasicAuthResolvedFromPasswordRef() {
        wm.stubFor(get(urlPathEqualTo("/management/engine")).willReturn(okJson("{\"version\":\"6.8.0\"}")));

        Map<String, Object> info = client.engineInfo(engine("auth-engine"));

        assertThat(info).containsEntry("version", "6.8.0");
        // base64(rest-admin:test) — the secret comes from the env var NAMED by password-ref
        wm.verify(getRequestedFor(urlPathEqualTo("/management/engine"))
                .withHeader("Authorization", equalTo("Basic cmVzdC1hZG1pbjp0ZXN0")));
    }

    @Test
    void latestProcessDefinitionByKeySendsLatestTrue() {
        // A plain size=1 does NOT return the latest version (name-ascending default) — the
        // migration default-target resolution needs latest=true. Pin it on the wire.
        wm.stubFor(get(urlPathEqualTo("/repository/process-definitions"))
                .willReturn(
                        okJson("{\"data\":[{\"id\":\"demoMigration:5:abc\",\"key\":\"demoMigration\",\"version\":5}],"
                                + "\"total\":1,\"start\":0,\"size\":1}")));

        var page = client.latestProcessDefinitionByKey(engine("latest-engine"), "demoMigration");

        assertThat(page.dataOrEmpty().get(0)).containsEntry("version", 5);
        wm.verify(getRequestedFor(urlPathEqualTo("/repository/process-definitions"))
                .withQueryParam("key", equalTo("demoMigration"))
                .withQueryParam("latest", equalTo("true"))
                .withQueryParam("size", equalTo("1")));
    }

    @Test
    void missingSecretEnvVarFailsLoudlyWithTheRefName() {
        EngineConfig engine = TestEngines.engine(
                "no-secret",
                wm.baseUrl(),
                TestEngines.basicAuth("rest-admin", "NOT_SET_ANYWHERE"),
                new Timeouts(1000, 1000, null));
        wm.stubFor(get(urlPathEqualTo("/management/engine")).willReturn(okJson("{}")));

        assertThatThrownBy(() -> client.engineInfo(engine))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT_SET_ANYWHERE");
    }

    @Test
    void readTimeoutSurfacesAsResourceAccessException() {
        wm.stubFor(get(urlPathEqualTo("/management/engine"))
                .willReturn(okJson("{}").withFixedDelay(2000)));

        assertThatThrownBy(() -> client.engineInfo(engine("slow-engine"))).isInstanceOf(ResourceAccessException.class);
    }

    @Test
    void neverFollowsRedirects() {
        wm.stubFor(get(urlPathEqualTo("/management/engine"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", wm.baseUrl() + "/redirected")));
        wm.stubFor(get(urlEqualTo("/redirected")).willReturn(okJson("{\"version\":\"evil\"}")));

        try {
            client.engineInfo(engine("redirect-engine"));
        } catch (RuntimeException expected) {
            // a surfaced 3xx is fine — following it is not
        }
        wm.verify(0, getRequestedFor(urlEqualTo("/redirected")));
    }

    @Test
    void breakerOpensAfterFailuresAndFastFailsWithoutNetworkCalls() {
        EngineConfig engine = engine("sick-engine");
        wm.stubFor(
                get(urlPathEqualTo("/management/engine")).willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> client.engineInfo(engine)).isInstanceOf(HttpServerErrorException.class);
        }
        // Window full (2/2 failures) → open. The third call must not touch the network.
        assertThatThrownBy(() -> client.engineInfo(engine)).isInstanceOf(CallNotPermittedException.class);
        wm.verify(2, getRequestedFor(urlPathEqualTo("/management/engine")));
    }

    @Test
    void breakerRecoversHalfOpenOnceTheEngineHeals() {
        EngineConfig engine = engine("healing-engine");
        wm.stubFor(
                get(urlPathEqualTo("/management/engine")).willReturn(aResponse().withStatus(500)));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> client.engineInfo(engine)).isInstanceOf(HttpServerErrorException.class);
        }
        assertThatThrownBy(() -> client.engineInfo(engine)).isInstanceOf(CallNotPermittedException.class);

        // Engine heals; after wait-duration (500ms) the breaker half-opens and the call succeeds.
        wm.stubFor(get(urlPathEqualTo("/management/engine")).willReturn(okJson("{\"version\":\"6.8.0\"}")));
        await().atMost(2, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(
                        () -> assertThatCode(() -> client.engineInfo(engine)).doesNotThrowAnyException());
    }

    @Test
    void clientErrorsDoNotTripTheBreaker() {
        EngineConfig engine = engine("gappy-engine");
        wm.stubFor(
                get(urlPathEqualTo("/management/engine")).willReturn(aResponse().withStatus(404)));

        // Well past the 2-call window: 404s are the expected negative answer of
        // capability probes and must never open the breaker.
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> client.engineInfo(engine)).isInstanceOf(HttpClientErrorException.class);
        }
        wm.verify(4, getRequestedFor(urlPathEqualTo("/management/engine")));
    }

    @Test
    void evictDropsCachedClientAndRemovesResilienceInstances() {
        // v2 Registry CRUD S3 reload seam: editing an engine is stale until its client + R4j
        // instances are evicted, so the NEXT call rebuilds against the new config.
        wm.stubFor(get(urlPathEqualTo("/management/engine")).willReturn(okJson("{\"version\":\"6.8.0\"}")));
        EngineConfig engine = engine("evict-me");
        client.engineInfo(engine); // materializes the cached client + the "engine" R4j instances

        assertThat(client.isClientCached("evict-me")).isTrue();
        assertThat(breakers.find("evict-me")).isPresent();
        assertThat(bulkheads.find("evict-me")).isPresent();

        client.evict("evict-me");

        assertThat(client.isClientCached("evict-me")).isFalse();
        assertThat(breakers.find("evict-me")).as("breaker REMOVED, not reset").isEmpty();
        assertThat(bulkheads.find("evict-me")).as("bulkhead REMOVED, not reset").isEmpty();

        // A later call rebuilds cleanly — fresh client + fresh breaker.
        assertThatCode(() -> client.engineInfo(engine)).doesNotThrowAnyException();
        assertThat(breakers.find("evict-me")).isPresent();
    }

    @Test
    void overdueTimerQuerySendsWholeSecondDueBefore() {
        // Regression (found on the dockerized engine): Flowable 400s on fractional seconds.
        EngineConfig engine = engine("timer-engine");
        wm.stubFor(get(urlPathEqualTo("/management/timer-jobs"))
                .willReturn(okJson("{\"data\":[],\"total\":3,\"start\":0,\"size\":0}")));

        long total = client.countOverdueTimers(engine, java.time.Instant.parse("2026-07-06T06:20:00.465Z"));

        assertThat(total).isEqualTo(3);
        wm.verify(getRequestedFor(urlPathEqualTo("/management/timer-jobs"))
                .withQueryParam("dueBefore", equalTo("2026-07-06T06:20:00Z")));
    }

    @Test
    void laneCountsUseSizeOneTotalsTrick() {
        EngineConfig engine = engine("lane-engine");
        wm.stubFor(get(urlPathEqualTo("/management/deadletter-jobs"))
                .willReturn(okJson("{\"data\":[{\"id\":\"j1\"}],\"total\":137,\"start\":0,\"size\":1}")));

        long total = client.countJobs(engine, FlowableEngineClient.JobLaneKind.DEADLETTER);

        assertThat(total).isEqualTo(137);
        wm.verify(getRequestedFor(urlPathEqualTo("/management/deadletter-jobs")).withQueryParam("size", equalTo("1")));
    }

    @Test
    void externalWorkerJobsHitTheSiblingExternalJobApiContextNotManagement() {
        // base-url ends in /service; the external-worker list lives at the /external-job-api
        // sibling (verified live). The client must swap the suffix, not append under /service.
        EngineConfig engine = TestEngines.engine("ew-engine", wm.baseUrl() + "/flowable-rest/service");
        wm.stubFor(
                get(urlPathEqualTo("/flowable-rest/external-job-api/jobs"))
                        .willReturn(
                                okJson(
                                        "{\"data\":[{\"id\":\"ew-1\",\"lockOwner\":\"worker-3\"}],\"total\":1,\"start\":0,\"size\":50}")));

        FlowableEngineClient.FlowablePage page =
                client.listExternalWorkerJobs(engine, java.util.Map.of("processInstanceId", "pi-1"), 0, 50);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.dataOrEmpty().get(0).get("lockOwner")).isEqualTo("worker-3");
        wm.verify(getRequestedFor(urlPathEqualTo("/flowable-rest/external-job-api/jobs"))
                .withQueryParam("processInstanceId", equalTo("pi-1")));
    }
}
