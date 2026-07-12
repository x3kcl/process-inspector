package io.inspector.client;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.Timeouts;
import io.inspector.support.TestEngines;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
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
 * CMMN-API path-traversal-guard + happy-path tests (F1 sibling-context id-in-path whitelist
 * bypass). The CMMN by-id calls interpolate an attacker-influenceable job/case id into the path.
 * A value carrying {@code /}, {@code ?}, {@code #} or a {@code ..} traversal must never re-target
 * the request to another engine path under the BFF's rest-admin creds — it is rejected at the
 * client boundary, BEFORE dialling.
 */
class CmmnApiClientTest {

    private WireMockServer wm;
    private MockEnvironment env;
    private CircuitBreakerRegistry breakers;
    private BulkheadRegistry bulkheads;
    private CmmnApiClient client;

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
        client = new CmmnApiClient(new GuardedCaller(
                env, breakers, bulkheads, new SimpleMeterRegistry(), new RecentEngineErrors(Clock.systemUTC())));
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
    void cmmnDeadLetterReadRejectsPathTraversalIdWithoutDialling() {
        assertThatThrownBy(() -> client.getCmmnDeadLetterJob(
                        engine("trav"), CallPriority.INTERACTIVE, "../../cmmn-repository/deployments"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void cmmnDeadLetterMutationsRejectReservedCharIdsWithoutDialling() {
        // '/' would re-path a retry; '?' would splice a query — both refused before any engine byte.
        assertThatThrownBy(() ->
                        client.moveCmmnDeadLetterJob(engine("mv"), CallPriority.INTERACTIVE, "1/../../management/jobs"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> client.deleteCmmnDeadLetterJob(engine("del"), CallPriority.INTERACTIVE, "1?cascade=true"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void cmmnCaseResolveRejectsTraversalIdWithoutDialling() {
        assertThatThrownBy(() -> client.getCmmnCaseInstance(engine("res"), CallPriority.INTERACTIVE, "..%2f..%2fadmin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void cmmnDeadLetterReadReachesTheDeadletterPathForAValidId() {
        wm.stubFor(get(urlPathEqualTo("/cmmn-api/cmmn-management/deadletter-jobs/abc-123"))
                .willReturn(okJson("{\"id\":\"abc-123\",\"caseInstanceId\":\"c1\"}")));

        Map<String, Object> job = client.getCmmnDeadLetterJob(engine("ok"), CallPriority.INTERACTIVE, "abc-123");

        assertThat(job).containsEntry("caseInstanceId", "c1");
        wm.verify(getRequestedFor(urlPathEqualTo("/cmmn-api/cmmn-management/deadletter-jobs/abc-123")));
    }
}
