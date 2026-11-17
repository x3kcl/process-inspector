package io.inspector.client;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;
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
 * Pins the external-worker job list (#474) against the RECORDED {@code …/external-job-api/jobs}
 * documents of a fresh 6.8 / 7.1 engine with two demoExternalWorker instances, one of them acquired
 * by {@code wire-worker} ({@code wire/{6.8,7.1}/external-worker-jobs.json}). The row is the same
 * shape as a management job row plus {@code correlationId} and the scope ids, so it reads as the
 * typed {@link JobRecord}/{@link JobPage}: the locked row keeps its lock, the unacquired row's lock
 * is null, {@code ""} tenant stays {@code ""}, and the page keeps its total.
 */
class ExternalWorkerJobWireShapeTest {

    private static final ObjectMapper STRICT = new ObjectMapper();

    private WireMockServer wm;
    private ExternalJobApiClient client;
    private EngineConfig engine;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("ENGINE_T_PASSWORD", "test");
        client = new ExternalJobApiClient(new GuardedCaller(
                env,
                CircuitBreakerRegistry.of(Map.of("engine", CircuitBreakerConfig.ofDefaults())),
                BulkheadRegistry.of(Map.of("engine", BulkheadConfig.ofDefaults())),
                new SimpleMeterRegistry(),
                new RecentEngineErrors(Clock.systemUTC())));
        engine = TestEngines.engine("ew", wm.baseUrl() + "/flowable-rest/service");
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private static String fixture(String major) throws IOException {
        try (InputStream in = ExternalWorkerJobWireShapeTest.class.getResourceAsStream(
                "/wire/" + major + "/external-worker-jobs.json")) {
            if (in == null) throw new IOException("missing fixture wire/" + major + "/external-worker-jobs.json");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void theStrictMapperReadsTheRecordedListAsAJobPage(String major) throws IOException {
        JobPage page = STRICT.readValue(fixture(major), JobPage.class);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.dataOrEmpty()).hasSize(2);
        JobRecord locked = page.dataOrEmpty().get(0);
        assertThat(locked.lockOwner()).isEqualTo("wire-worker"); // the "who is holding this" tell
        assertThat(locked.lockExpirationTime()).isNotNull();
        assertThat(locked.elementId()).isEqualTo("chargeViaWorker");
        assertThat(locked.elementName()).isEqualTo("Charge via worker");
        assertThat(locked.retries()).isEqualTo(3);
        assertThat(locked.tenantId()).isEqualTo(""); // "" stays "", never null
        assertThat(locked.exceptionMessage()).isNull();
        assertThat(locked.dueDate()).isNull();
        assertThat(locked.processInstanceId()).isNotBlank();
        assertThat(locked.executionId()).isNotBlank();
        assertThat(locked.processDefinitionId()).startsWith("demoExternalWorker:1:");
        JobRecord unacquired = page.dataOrEmpty().get(1);
        assertThat(unacquired.lockOwner()).isNull();
        assertThat(unacquired.lockExpirationTime()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    @SuppressWarnings("unchecked")
    void theClientAnswersTheTypedPageOfTheRecordedList(String major) throws IOException {
        String body = fixture(major);
        wm.stubFor(get(urlPathEqualTo("/flowable-rest/external-job-api/jobs")).willReturn(okJson(body)));
        Map<String, Object> recorded = new ObjectMapper().readValue(body, Map.class);
        JobPage expected = new JobPage(JobRecords.jobs((List<Map<String, Object>>) recorded.get("data")), 2, 0, 2);

        Object page = client.listExternalWorkerJobs(engine, CallPriority.INTERACTIVE, Map.of(), 0, 50);

        assertThat(page).isEqualTo(expected);
    }
}
