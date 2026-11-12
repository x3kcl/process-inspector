package io.inspector.client;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Rung 2: the case-migration reads (#500) hit the paths and parameters measured on flowable-rest
 * 6.8.0 / 7.1.0, and decode the recorded shapes (the batch rows are a 7.1.0 capture).
 */
class CaseMigrationClientTest {

    private WireMockServer wm;
    private CaseMigrationClient client;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("ENGINE_T_PASSWORD", "test");
        client = new CaseMigrationClient(new GuardedCaller(
                env,
                CircuitBreakerRegistry.of(Map.of("engine", CircuitBreakerConfig.ofDefaults())),
                BulkheadRegistry.of(Map.of(
                        "engine",
                        BulkheadConfig.custom()
                                .maxConcurrentCalls(8)
                                .maxWaitDuration(Duration.ofSeconds(5))
                                .build())),
                new SimpleMeterRegistry(),
                new RecentEngineErrors(Clock.systemUTC())));
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
    void theCohortListSendsOnlyTheFiltersGivenAndEncodesTheLikePattern() {
        wm.stubFor(get(urlPathEqualTo("/cmmn-api/cmmn-runtime/case-instances"))
                .willReturn(okJson("{\"data\":[{\"id\":\"case-1\"},{\"id\":\"case-2\"}],\"total\":7,\"start\":0,"
                        + "\"sort\":\"id\",\"order\":\"asc\",\"size\":2}")));

        FlowablePage page = client.listRunningCases(
                engine("list"),
                CallPriority.INTERACTIVE,
                "def-1",
                null,
                "%ORDER 7%",
                "2026-11-01T00:00:00Z",
                null,
                500);

        assertThat(page.total()).isEqualTo(7);
        assertThat(page.dataOrEmpty()).extracting(row -> row.get("id")).containsExactly("case-1", "case-2");
        wm.verify(getRequestedFor(urlPathEqualTo("/cmmn-api/cmmn-runtime/case-instances"))
                .withQueryParam("caseDefinitionId", equalTo("def-1"))
                .withQueryParam("businessKeyLike", equalTo("%ORDER 7%"))
                .withQueryParam("startedAfter", equalTo("2026-11-01T00:00:00Z"))
                .withQueryParam("sort", equalTo("id"))
                .withQueryParam("size", equalTo("500")));
        assertThat(wm.getAllServeEvents().get(0).getRequest().getUrl())
                .doesNotContain("businessKey=")
                .doesNotContain("startedBefore");
    }

    @Test
    void endedCasesAreCountedOnlyAndVersionsComeNewestFirst() {
        wm.stubFor(get(urlPathEqualTo("/cmmn-api/cmmn-history/historic-case-instances"))
                .willReturn(okJson("{\"data\":[{\"id\":\"case-9\"}],\"total\":12,\"start\":0,\"size\":1}")));
        wm.stubFor(get(urlPathEqualTo("/cmmn-api/cmmn-repository/case-definitions"))
                .willReturn(okJson("{\"data\":[{\"id\":\"def-2\",\"version\":2},{\"id\":\"def-1\",\"version\":1}],"
                        + "\"total\":2,\"start\":0,\"size\":2}")));

        assertThat(client.countEndedCases(engine("count"), CallPriority.INTERACTIVE, "def-1"))
                .isEqualTo(12);
        assertThat(client.listCaseDefinitionVersionsDesc(engine("count"), CallPriority.INTERACTIVE, "demoCase", 50)
                        .dataOrEmpty())
                .hasSize(2);

        wm.verify(getRequestedFor(urlPathEqualTo("/cmmn-api/cmmn-history/historic-case-instances"))
                .withQueryParam("caseDefinitionId", equalTo("def-1"))
                .withQueryParam("finished", equalTo("true"))
                .withQueryParam("size", equalTo("1")));
        wm.verify(getRequestedFor(urlPathEqualTo("/cmmn-api/cmmn-repository/case-definitions"))
                .withQueryParam("key", equalTo("demoCase"))
                .withQueryParam("sort", equalTo("version"))
                .withQueryParam("order", equalTo("desc")));
    }

    @Test
    void caseMigrationBatchesLiveBehindTheProcessApiAndPartsAreABareArray() {
        // Recorded on flowable-rest 7.1.0 after POST case-definitions/{id}/batch-migrate (#500).
        wm.stubFor(get(urlPathEqualTo("/management/batches"))
                .willReturn(okJson("{\"data\":[{\"id\":\"21153102\",\"batchType\":\"caseMigration\","
                        + "\"searchKey\":\"def-1\",\"searchKey2\":\"def-2\","
                        + "\"createTime\":\"2026-11-12T17:40:19.532Z\",\"completeTime\":null,"
                        + "\"status\":\"inProgress\",\"tenantId\":null}],\"total\":1,\"start\":0,\"sort\":\"id\","
                        + "\"order\":\"asc\",\"size\":1}")));
        wm.stubFor(get(urlPathEqualTo("/management/batches/21153102/batch-parts"))
                .willReturn(okJson("[{\"id\":\"21155813\",\"batchId\":\"21153102\",\"batchType\":\"caseMigration\","
                        + "\"scopeId\":\"0c510773\",\"subScopeId\":null,\"scopeType\":\"cmmn\","
                        + "\"completeTime\":\"2026-11-12T17:40:19.609Z\",\"status\":\"success\"}]")));

        FlowablePage batches = client.listCaseMigrationBatches(engine("batch"), CallPriority.INTERACTIVE, "def-1", 20);
        List<Map<String, Object>> parts = client.listBatchParts(engine("batch"), CallPriority.INTERACTIVE, "21153102");

        assertThat(batches.dataOrEmpty().get(0)).containsEntry("searchKey2", "def-2");
        assertThat(parts).hasSize(1);
        assertThat(parts.get(0)).containsEntry("scopeId", "0c510773").containsEntry("status", "success");
        wm.verify(getRequestedFor(urlPathEqualTo("/management/batches"))
                .withQueryParam("batchType", equalTo("caseMigration"))
                .withQueryParam("searchKey", equalTo("def-1")));
    }

    @Test
    void aBatchIdThatWouldRePathTheRequestIsRefusedWithoutDialling() {
        assertThatThrownBy(() -> client.listBatchParts(engine("trav"), CallPriority.INTERACTIVE, "../../runtime/tasks"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }
}
