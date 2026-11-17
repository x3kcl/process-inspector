package io.inspector.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * #718: 6.8 and 7.1 LIST a null-valued case variable but answer its single-variable GET with 404
 * (live-probed on both majors). The single read is the CAS pre-read of edit-case-variable, so it
 * must fall back to the list: a name the list shows is the null row, a name the list lacks is
 * still "no such variable".
 */
class CmmnNullCaseVariableReadTest {

    private static final String CASE = "case-1";
    private static final String BASE = "/cmmn-api/cmmn-runtime/case-instances/" + CASE + "/variables";

    private WireMockServer wm;
    private CmmnApiClient client;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("ENGINE_T_PASSWORD", "test");
        client = new CmmnApiClient(new GuardedCaller(
                env,
                CircuitBreakerRegistry.of(Map.of("engine", CircuitBreakerConfig.ofDefaults())),
                BulkheadRegistry.of(Map.of("engine", BulkheadConfig.ofDefaults())),
                new SimpleMeterRegistry(),
                new RecentEngineErrors(Clock.systemUTC())));
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private EngineConfig engine() {
        return TestEngines.engine(
                "cmmn-null-var",
                wm.baseUrl(),
                TestEngines.basicAuth("rest-admin", "ENGINE_T_PASSWORD"),
                new Timeouts(1000, 1000, null));
    }

    private static String fixture(String major, String name) throws IOException {
        try (InputStream in = CmmnNullCaseVariableReadTest.class.getResourceAsStream("/wire/" + major + "/" + name)) {
            if (in == null) throw new IOException("missing fixture wire/" + major + "/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void aNullValuedCaseVariableThatTheSingleReadMissesIsReadFromTheList(String major) throws IOException {
        // Given: the single read 404s for every name, the list holds the recorded rows incl. aNull
        wm.stubFor(get(urlPathEqualTo(BASE + "/aNull")).willReturn(aResponse().withStatus(404)));
        wm.stubFor(get(urlPathEqualTo(BASE + "/gone")).willReturn(aResponse().withStatus(404)));
        wm.stubFor(get(urlPathEqualTo(BASE)).willReturn(okJson(fixture(major, "cmmn-case-variables.json"))));

        // When / Then: the listed null row is found, an unlisted name is still absent
        assertThat(client.getCaseVariable(engine(), CallPriority.INTERACTIVE, CASE, "aNull"))
                .isEqualTo(new VariableRecord("aNull", null, null, null, null));
        assertThat(client.getCaseVariable(engine(), CallPriority.INTERACTIVE, CASE, "gone"))
                .isNull();
    }
}
