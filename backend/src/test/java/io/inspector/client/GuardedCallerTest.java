package io.inspector.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
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
import io.inspector.client.GuardedCaller.CallPriority;
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
 * redirect policy, the per-engine circuit breaker + evict seam, and the X-Forwarded-User
 * send-side. These are GuardedCaller cross-cutting concerns; {@link ProcessApiClient} is used
 * as the call vehicle only because it offers convenient thin wrappers (engineInfo,
 * moveDeadLetterJob) — every *ApiClient facade shares the SAME resilience core underneath.
 * Join/paging semantics are rung 4 (never mocked — engine-harness iron rule).
 */
class GuardedCallerTest {

    private WireMockServer wm;
    private MockEnvironment env;
    private CircuitBreakerRegistry breakers;
    private BulkheadRegistry bulkheads;
    private GuardedCaller guarded;
    private ProcessApiClient client;

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
        guarded = new GuardedCaller(env, breakers, bulkheads);
        client = new ProcessApiClient(guarded);
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
    void emitsBasicAuthResolvedFromPasswordRef() {
        wm.stubFor(get(urlPathEqualTo("/management/engine")).willReturn(okJson("{\"version\":\"6.8.0\"}")));

        Map<String, Object> info = client.engineInfo(engine("auth-engine"), CallPriority.INTERACTIVE);

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

        assertThatThrownBy(() -> client.engineInfo(engine, CallPriority.INTERACTIVE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT_SET_ANYWHERE");
    }

    @Test
    void readTimeoutSurfacesAsResourceAccessException() {
        wm.stubFor(get(urlPathEqualTo("/management/engine"))
                .willReturn(okJson("{}").withFixedDelay(2000)));

        assertThatThrownBy(() -> client.engineInfo(engine("slow-engine"), CallPriority.INTERACTIVE))
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    void neverFollowsRedirects() {
        wm.stubFor(get(urlPathEqualTo("/management/engine"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", wm.baseUrl() + "/redirected")));
        wm.stubFor(get(urlEqualTo("/redirected")).willReturn(okJson("{\"version\":\"evil\"}")));

        try {
            client.engineInfo(engine("redirect-engine"), CallPriority.INTERACTIVE);
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
            assertThatThrownBy(() -> client.engineInfo(engine, CallPriority.INTERACTIVE))
                    .isInstanceOf(HttpServerErrorException.class);
        }
        // Window full (2/2 failures) → open. The third call must not touch the network.
        assertThatThrownBy(() -> client.engineInfo(engine, CallPriority.INTERACTIVE))
                .isInstanceOf(CallNotPermittedException.class);
        wm.verify(2, getRequestedFor(urlPathEqualTo("/management/engine")));
    }

    @Test
    void breakerRecoversHalfOpenOnceTheEngineHeals() {
        EngineConfig engine = engine("healing-engine");
        wm.stubFor(
                get(urlPathEqualTo("/management/engine")).willReturn(aResponse().withStatus(500)));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> client.engineInfo(engine, CallPriority.INTERACTIVE))
                    .isInstanceOf(HttpServerErrorException.class);
        }
        assertThatThrownBy(() -> client.engineInfo(engine, CallPriority.INTERACTIVE))
                .isInstanceOf(CallNotPermittedException.class);

        // Engine heals; after wait-duration (500ms) the breaker half-opens and the call succeeds.
        wm.stubFor(get(urlPathEqualTo("/management/engine")).willReturn(okJson("{\"version\":\"6.8.0\"}")));
        await().atMost(2, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThatCode(() -> client.engineInfo(engine, CallPriority.INTERACTIVE))
                        .doesNotThrowAnyException());
    }

    @Test
    void clientErrorsDoNotTripTheBreaker() {
        EngineConfig engine = engine("gappy-engine");
        wm.stubFor(
                get(urlPathEqualTo("/management/engine")).willReturn(aResponse().withStatus(404)));

        // Well past the 2-call window: 404s are the expected negative answer of
        // capability probes and must never open the breaker.
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> client.engineInfo(engine, CallPriority.INTERACTIVE))
                    .isInstanceOf(HttpClientErrorException.class);
        }
        wm.verify(4, getRequestedFor(urlPathEqualTo("/management/engine")));
    }

    @Test
    void evictDropsCachedClientAndRemovesResilienceInstances() {
        // v2 Registry CRUD S3 reload seam: editing an engine is stale until its client + R4j
        // instances are evicted, so the NEXT call rebuilds against the new config.
        wm.stubFor(get(urlPathEqualTo("/management/engine")).willReturn(okJson("{\"version\":\"6.8.0\"}")));
        EngineConfig engine = engine("evict-me");
        client.engineInfo(
                engine, CallPriority.INTERACTIVE); // materializes the cached client + the "engine" R4j instances

        assertThat(guarded.isClientCached("evict-me")).isTrue();
        assertThat(breakers.find("evict-me")).isPresent();
        assertThat(bulkheads.find("evict-me")).isPresent();

        guarded.evict("evict-me");

        assertThat(guarded.isClientCached("evict-me")).isFalse();
        assertThat(breakers.find("evict-me")).as("breaker REMOVED, not reset").isEmpty();
        assertThat(bulkheads.find("evict-me")).as("bulkhead REMOVED, not reset").isEmpty();

        // A later call rebuilds cleanly — fresh client + fresh breaker.
        assertThatCode(() -> client.engineInfo(engine, CallPriority.INTERACTIVE))
                .doesNotThrowAnyException();
        assertThat(breakers.find("evict-me")).isPresent();
    }

    /* ---------------- X-Forwarded-User send-side (M4-CLOSEOUT §2 / S4) ---------------- */

    private EngineConfig forwardEngine(String id) {
        return TestEngines.forwardUserEngine(
                id,
                wm.baseUrl(),
                TestEngines.basicAuth("rest-admin", "ENGINE_T_PASSWORD"),
                new Timeouts(1000, 1000, null));
    }

    @Test
    void forwardsUserHeaderOnWriteWhenEngineOptedInAndActorSet() {
        wm.stubFor(post(urlPathEqualTo("/management/deadletter-jobs/j1"))
                .willReturn(aResponse().withStatus(200)));

        ForwardedActor.set("alice");
        client.moveDeadLetterJob(forwardEngine("fwd-on"), CallPriority.INTERACTIVE, "j1");

        wm.verify(postRequestedFor(urlPathEqualTo("/management/deadletter-jobs/j1"))
                .withHeader("X-Forwarded-User", equalTo("alice")));
    }

    @Test
    void omitsUserHeaderWhenEngineNotOptedIn() {
        wm.stubFor(post(urlPathEqualTo("/management/deadletter-jobs/j1"))
                .willReturn(aResponse().withStatus(200)));

        // A plain (non-forward-user) engine never carries the header, even with an actor set.
        ForwardedActor.set("alice");
        client.moveDeadLetterJob(engine("fwd-off"), CallPriority.INTERACTIVE, "j1");

        wm.verify(postRequestedFor(urlPathEqualTo("/management/deadletter-jobs/j1"))
                .withoutHeader("X-Forwarded-User"));
    }

    @Test
    void omitsUserHeaderWhenNoActorSet() {
        wm.stubFor(post(urlPathEqualTo("/management/deadletter-jobs/j1"))
                .willReturn(aResponse().withStatus(200)));

        // Opted-in engine but no actor on the thread (holder empty) → header dropped, never blank.
        client.moveDeadLetterJob(forwardEngine("fwd-on-noactor"), CallPriority.INTERACTIVE, "j1");

        wm.verify(postRequestedFor(urlPathEqualTo("/management/deadletter-jobs/j1"))
                .withoutHeader("X-Forwarded-User"));
    }

    @Test
    void forwardedUserHeaderIsSanitized() {
        wm.stubFor(post(urlPathEqualTo("/management/deadletter-jobs/j1"))
                .willReturn(aResponse().withStatus(200)));

        // CR/LF would be a header-injection vector — the holder strips it before it reaches the wire.
        ForwardedActor.set("al\r\nice");
        client.moveDeadLetterJob(forwardEngine("fwd-sanitize"), CallPriority.INTERACTIVE, "j1");

        wm.verify(postRequestedFor(urlPathEqualTo("/management/deadletter-jobs/j1"))
                .withHeader("X-Forwarded-User", equalTo("alice")));
    }

    @Test
    void neverForwardsUserHeaderOnTheReadClient() {
        wm.stubFor(get(urlPathEqualTo("/management/engine")).willReturn(okJson("{\"version\":\"6.8.0\"}")));

        // The interceptor is WRITE-client only — a read on a forward-user engine carries no identity.
        ForwardedActor.set("alice");
        client.engineInfo(forwardEngine("fwd-read"), CallPriority.INTERACTIVE);

        wm.verify(getRequestedFor(urlPathEqualTo("/management/engine")).withoutHeader("X-Forwarded-User"));
    }
}
