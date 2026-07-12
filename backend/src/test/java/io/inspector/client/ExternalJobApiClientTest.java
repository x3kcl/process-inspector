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
 * External-worker-API wire shape: the list call must hit the SIBLING {@code /external-job-api}
 * context, not a path nested under {@code /service/management} (verified live).
 */
class ExternalJobApiClientTest {

    private WireMockServer wm;
    private MockEnvironment env;
    private CircuitBreakerRegistry breakers;
    private BulkheadRegistry bulkheads;
    private ExternalJobApiClient client;

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
        client = new ExternalJobApiClient(new GuardedCaller(
                env, breakers, bulkheads, new SimpleMeterRegistry(), new RecentEngineErrors(Clock.systemUTC())));
    }

    @AfterEach
    void tearDown() {
        ForwardedActor.clear();
        wm.stop();
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

        FlowablePage page = client.listExternalWorkerJobs(
                engine, CallPriority.INTERACTIVE, java.util.Map.of("processInstanceId", "pi-1"), 0, 50);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.dataOrEmpty().get(0).get("lockOwner")).isEqualTo("worker-3");
        wm.verify(getRequestedFor(urlPathEqualTo("/flowable-rest/external-job-api/jobs"))
                .withQueryParam("processInstanceId", equalTo("pi-1")));
    }
}
