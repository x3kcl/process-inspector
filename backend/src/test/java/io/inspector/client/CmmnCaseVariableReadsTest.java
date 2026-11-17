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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * The CMMN case-variable and case-identity-link reads through the REAL client and its message
 * converter (#474), replaying the recorded 6.8 / 7.1 documents: the value survives the HTTP layer at
 * its JSON width, a null-valued variable is a row, a 404 is "no such variable" / "no rows".
 */
class CmmnCaseVariableReadsTest {

    private static final long BEYOND_DOUBLE = 9007199254740993L;
    private static final String CASE = "case-1";

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
                "cmmn-vars",
                wm.baseUrl(),
                TestEngines.basicAuth("rest-admin", "ENGINE_T_PASSWORD"),
                new Timeouts(1000, 1000, null));
    }

    private static String fixture(String major, String name) throws IOException {
        try (InputStream in = CmmnCaseVariableReadsTest.class.getResourceAsStream("/wire/" + major + "/" + name)) {
            if (in == null) throw new IOException("missing fixture wire/" + major + "/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void listCaseVariablesKeepsTheValueWidthAndTheNullRow(String major) throws IOException {
        wm.stubFor(get(urlPathEqualTo("/cmmn-api/cmmn-runtime/case-instances/" + CASE + "/variables"))
                .willReturn(okJson(fixture(major, "cmmn-case-variables.json"))));

        List<VariableRecord> rows = client.listCaseVariables(engine(), CallPriority.INTERACTIVE, CASE);

        assertThat(rows).hasSize(9);
        assertThat(rows)
                .contains(
                        new VariableRecord("aLong", "long", BEYOND_DOUBLE, null, null),
                        new VariableRecord("aSmallLong", "long", 5, null, null),
                        new VariableRecord("aDate", "date", "2026-01-02T03:04:05Z", null, null),
                        new VariableRecord("aNull", null, null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void getCaseVariableReadsTheEnvelopeAndA404IsNull(String major) throws IOException {
        wm.stubFor(get(urlPathEqualTo("/cmmn-api/cmmn-runtime/case-instances/" + CASE + "/variables/aLong"))
                .willReturn(okJson(fixture(major, "cmmn-case-variable.json"))));
        wm.stubFor(get(urlPathEqualTo("/cmmn-api/cmmn-runtime/case-instances/" + CASE + "/variables/gone"))
                .willReturn(aResponse().withStatus(404)));

        assertThat(client.getCaseVariable(engine(), CallPriority.INTERACTIVE, CASE, "aLong"))
                .isEqualTo(new VariableRecord("aLong", "long", BEYOND_DOUBLE, null, null));
        assertThat(client.getCaseVariable(engine(), CallPriority.INTERACTIVE, CASE, "gone"))
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void listCaseIdentityLinksReadsTheBareArrayAndA404IsEmpty(String major) throws IOException {
        wm.stubFor(get(urlPathEqualTo("/cmmn-api/cmmn-runtime/case-instances/" + CASE + "/identitylinks"))
                .willReturn(okJson(fixture(major, "cmmn-case-identity-links.json"))));
        wm.stubFor(get(urlPathEqualTo("/cmmn-api/cmmn-runtime/case-instances/gone/identitylinks"))
                .willReturn(aResponse().withStatus(404)));
        wm.stubFor(get(urlPathEqualTo("/cmmn-api/cmmn-runtime/case-instances/gone/variables"))
                .willReturn(aResponse().withStatus(404)));

        assertThat(client.listCaseIdentityLinks(engine(), CallPriority.INTERACTIVE, CASE))
                .extracting(CaseIdentityLinkRecord::user, CaseIdentityLinkRecord::type, CaseIdentityLinkRecord::group)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("rest-admin", "starter", null),
                        org.assertj.core.groups.Tuple.tuple("kermit", "participant", null));
        assertThat(client.listCaseIdentityLinks(engine(), CallPriority.INTERACTIVE, "gone"))
                .isEmpty();
        assertThat(client.listCaseVariables(engine(), CallPriority.INTERACTIVE, "gone"))
                .isEmpty();
    }
}
