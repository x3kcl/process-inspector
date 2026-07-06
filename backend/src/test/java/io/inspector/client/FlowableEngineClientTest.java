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
        client = new FlowableEngineClient(env, breakers);
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
}
