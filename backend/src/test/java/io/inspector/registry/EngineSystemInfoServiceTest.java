package io.inspector.registry;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.client.GuardedCaller;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.RecentEngineErrors;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.Timeouts;
import io.inspector.registry.EngineSystemInfoService.EngineSystemInfo;
import io.inspector.support.TestEngines;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Rung 2 for #627: the recorded {@code /management/tables} answer from real 6.8 and 7.1 engines is a
 * bare array. The service must turn it into row counts, and label (not hide) a section that fails.
 */
class EngineSystemInfoServiceTest {

    private WireMockServer wm;
    private EngineSystemInfoService service;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("ENGINE_T_PASSWORD", "test");
        ProcessApiClient client = new ProcessApiClient(new GuardedCaller(
                env,
                CircuitBreakerRegistry.of(Map.of("engine", CircuitBreakerConfig.ofDefaults())),
                BulkheadRegistry.of(Map.of("engine", BulkheadConfig.ofDefaults())),
                new SimpleMeterRegistry(),
                new RecentEngineErrors(Clock.systemUTC())));
        EngineConfig engine = TestEngines.engine(
                "wire-engine",
                wm.baseUrl(),
                TestEngines.basicAuth("rest-admin", "ENGINE_T_PASSWORD"),
                new Timeouts(2000, 2000, null));
        EngineRegistry registry = mock(EngineRegistry.class);
        when(registry.resolveOrNotFound("wire-engine")).thenReturn(engine);
        service = new EngineSystemInfoService(registry, client, null, null, env);
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private static String fixture(String major, String name) throws IOException {
        try (InputStream in = EngineSystemInfoServiceTest.class.getResourceAsStream("/wire/" + major + "/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void stubEngineAndProperties(String major) throws IOException {
        wm.stubFor(get(urlPathEqualTo("/management/engine")).willReturn(okJson(fixture(major, "engine.json"))));
        wm.stubFor(get(urlPathEqualTo("/management/properties")).willReturn(okJson("{\"schema.version\":\"x\"}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void recordedBareArrayBecomesTableRowCounts(String major) throws IOException {
        stubEngineAndProperties(major);
        wm.stubFor(
                get(urlPathEqualTo("/management/tables")).willReturn(okJson(fixture(major, "management-tables.json"))));

        EngineSystemInfo info = service.load("wire-engine", true);

        assertThat(info.failures()).isEmpty();
        assertThat(info.tables())
                .isNotEmpty()
                .allSatisfy(t -> assertThat(t.count()).isNotNull());
        assertThat(info.tables())
                .extracting(EngineSystemInfoService.TableCount::name)
                .contains("ACT_RE_PROCDEF", "ACT_RU_DEADLETTER_JOB");
    }

    @Test
    void aRefusedTablesCallIsLabelledNotHidden() throws IOException {
        stubEngineAndProperties("7.1");
        wm.stubFor(
                get(urlPathEqualTo("/management/tables")).willReturn(aResponse().withStatus(403)));

        EngineSystemInfo info = service.load("wire-engine", true);

        assertThat(info.failures()).containsExactly("tables");
        assertThat(info.tables()).isEmpty();
        assertThat(info.properties()).isNotEmpty();
    }

    @Test
    void anEmptyTablesAnswerIsLabelledNotShownAsNoTables() throws IOException {
        // Every engine has tables; an empty body means the answer is unusable, not "no tables".
        stubEngineAndProperties("7.1");
        wm.stubFor(
                get(urlPathEqualTo("/management/tables")).willReturn(aResponse().withStatus(200)));

        EngineSystemInfo info = service.load("wire-engine", true);

        assertThat(info.failures()).containsExactly("tables");
        assertThat(info.tables()).isEmpty();
    }
}
