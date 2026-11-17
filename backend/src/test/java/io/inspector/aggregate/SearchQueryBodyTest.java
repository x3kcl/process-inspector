package io.inspector.aggregate;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.GuardedCaller;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.RecentEngineErrors;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.Timeouts;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.dto.SearchRequest.VariableFilter;
import io.inspector.dto.SearchResponse;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import io.inspector.support.WireJson;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Characterization of the SEARCH query bodies (#720): what {@link SearchService} — through
 * {@code SearchEnginePlan} — POSTs to {@code /query/historic-process-instances} and
 * {@code /query/process-instances}, read off the wire of a WireMock engine and compared as
 * canonical JSON ({@link WireJson}: keys sorted, values and their JSON types untouched, absent keys
 * absent). It knows nothing about how the body is built, so it holds for the map bodies of
 * {@code main} and for the typed query alike. Covered: paging and sort keys, the variable filter
 * objects, the tenant filter, the definition pushdown, the one-sided {@code finished} narrowing,
 * the id-set legs, and the two 6.3 cliffs (the {@code processBusinessKeyLike} canary and the
 * runtime {@code processInstanceIds} fallback). No search body carries
 * {@code includeProcessVariables} — the BFF never asks the engine to inline variables.
 */
class SearchQueryBodyTest {

    private static final String HISTORIC = "/query/historic-process-instances";
    private static final String RUNTIME = "/query/process-instances";
    private static final String EMPTY_PAGE = "{\"data\":[],\"total\":0,\"start\":0,\"size\":0}";
    private static final String OPEN_ROW =
            "{\"id\":\"pi-1\",\"processDefinitionId\":\"demoOrder:3:abc\",\"startTime\":\"2026-11-10T08:00:00.000Z\"}";
    private static final String ENDED_ROW = "{\"id\":\"pi-9\",\"processDefinitionId\":\"demoOrder:3:abc\","
            + "\"startTime\":\"2026-11-10T08:00:00.000Z\",\"endTime\":\"2026-11-10T09:00:00.000Z\"}";

    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final ProtectedInstanceRepository protectedRepo = mock(ProtectedInstanceRepository.class);
    private WireMockServer wm;
    private SearchService service;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        wm.stubFor(any(anyUrl()).atPriority(10).willReturn(okJson(EMPTY_PAGE)));
        ProcessApiClient client = new ProcessApiClient(new GuardedCaller(
                new MockEnvironment().withProperty("ENGINE_T_PASSWORD", "test"),
                CircuitBreakerRegistry.of(Map.of(
                        "engine", CircuitBreakerConfig.ofDefaults(), "deep-page", CircuitBreakerConfig.ofDefaults())),
                BulkheadRegistry.of(
                        Map.of("engine", BulkheadConfig.ofDefaults(), "deep-page", BulkheadConfig.ofDefaults())),
                new SimpleMeterRegistry(),
                new RecentEngineErrors(Clock.systemUTC())));
        service = new SearchService(
                registry, client, new InspectorProperties(4, 10, null, null, null, List.of()), protectedRepo);
        when(protectedRepo.findAllById(anyIterable())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        wm.stop();
    }

    /* ------------------------------ the shared historic body ------------------------------ */

    @Test
    void aSearchWithNoFilterSendsOnlyPagingAndTheNewestFirstSort() {
        fleet(null);

        service.search(req(List.of(InstanceStatus.ACTIVE, InstanceStatus.COMPLETED), null, null, null, null, 50));

        assertThat(bodies(HISTORIC)).containsExactly("{\"order\":\"desc\",\"size\":50,\"sort\":\"startTime\"}");
        assertThat(bodies(RUNTIME)).isEmpty();
    }

    @Test
    void everyScalarFilterTheTenantAndTheVariableFiltersGoIntoOneHistoricBody() {
        fleet("acme");
        SearchRequest request = new SearchRequest(
                null,
                List.of(InstanceStatus.ACTIVE, InstanceStatus.COMPLETED),
                null,
                null,
                "ORD-1",
                null,
                "2026-11-01T00:00:00Z",
                "2026-11-17T00:00:00Z",
                null,
                null,
                null,
                null,
                null,
                List.of(
                        new VariableFilter("amount", 9007199254740993L, "greaterThan", "long"),
                        new VariableFilter("orderNo", "42", null, null),
                        new VariableFilter("note", null, "equals", null)),
                null,
                50,
                null,
                null);

        service.search(request);

        assertThat(bodies(HISTORIC))
                .containsExactly("{\"order\":\"desc\",\"processBusinessKey\":\"ORD-1\",\"size\":50,"
                        + "\"sort\":\"startTime\",\"startedAfter\":\"2026-11-01T00:00:00Z\","
                        + "\"startedBefore\":\"2026-11-17T00:00:00Z\",\"tenantId\":\"acme\",\"variables\":["
                        + "{\"name\":\"amount\",\"operation\":\"greaterThan\",\"type\":\"long\",\"value\":9007199254740993},"
                        + "{\"name\":\"orderNo\",\"operation\":\"equals\",\"value\":\"42\"},"
                        + "{\"name\":\"note\",\"operation\":\"equals\",\"value\":null}]}");
    }

    @Test
    void aRequestedPageSizeAboveTheEngineCapIsSentAsTheCap() {
        fleet(null);

        service.search(req(List.of(InstanceStatus.ACTIVE, InstanceStatus.COMPLETED), null, null, null, null, 9999));
        service.search(req(List.of(InstanceStatus.ACTIVE, InstanceStatus.COMPLETED), null, null, null, null, null));

        assertThat(bodies(HISTORIC))
                .containsExactly(
                        "{\"order\":\"desc\",\"size\":200,\"sort\":\"startTime\"}",
                        "{\"order\":\"desc\",\"size\":200,\"sort\":\"startTime\"}");
    }

    @Test
    void aOneSidedStatusSetNarrowsFinishedAndATwoSidedOneLeavesTheKeyOut() {
        fleet(null);

        service.search(req(List.of(InstanceStatus.COMPLETED), null, null, null, null, 50));
        service.search(req(List.of(InstanceStatus.ACTIVE, InstanceStatus.SUSPENDED), null, null, null, null, 50));

        assertThat(bodies(HISTORIC))
                .containsExactly(
                        "{\"finished\":true,\"order\":\"desc\",\"size\":50,\"sort\":\"startTime\"}",
                        "{\"finished\":false,\"order\":\"desc\",\"size\":50,\"sort\":\"startTime\"}");
    }

    @Test
    void aDefinitionKeyIsSentAsTheKeyAndAKeyWithAVersionAsTheOneResolvedDefinitionId() {
        fleet(null);
        wm.stubFor(get(urlPathEqualTo("/repository/process-definitions"))
                .willReturn(okJson("{\"data\":[{\"id\":\"demoOrder:3:abc\",\"key\":\"demoOrder\",\"version\":3}],"
                        + "\"total\":1,\"start\":0,\"size\":1}")));

        service.search(
                req(List.of(InstanceStatus.ACTIVE, InstanceStatus.COMPLETED), "demoOrder", null, null, null, 50));
        service.search(req(List.of(InstanceStatus.ACTIVE, InstanceStatus.COMPLETED), "demoOrder", 3, null, null, 50));

        assertThat(bodies(HISTORIC))
                .containsExactly(
                        "{\"order\":\"desc\",\"processDefinitionKey\":\"demoOrder\",\"size\":50,\"sort\":\"startTime\"}",
                        "{\"order\":\"desc\",\"processDefinitionId\":\"demoOrder:3:abc\",\"size\":50,"
                                + "\"sort\":\"startTime\"}");
    }

    @Test
    void theFirstPageCarriesNoStartKeyAndTheNextPageCarriesTheOffset() {
        fleet(null);
        wm.stubFor(post(urlPathEqualTo(HISTORIC)).willReturn(okJson(page(3, ENDED_ROW, ENDED_ROW))));

        service.resolveAllMatching(req(List.of(InstanceStatus.COMPLETED), null, null, null, null, 2), 1000);

        assertThat(bodies(HISTORIC))
                .containsExactly(
                        "{\"finished\":true,\"order\":\"desc\",\"size\":2,\"sort\":\"startTime\"}",
                        "{\"finished\":true,\"order\":\"desc\",\"size\":2,\"sort\":\"startTime\",\"start\":2}");
    }

    /* ------------------------------ the id-set legs ------------------------------ */

    @Test
    void theSuspendedLookupAsksTheRuntimeQueryForExactlyThePageOpenIdsWithTheTenant() {
        fleet("acme");
        wm.stubFor(post(urlPathEqualTo(HISTORIC)).willReturn(okJson(page(2, OPEN_ROW, ENDED_ROW))));

        service.search(req(List.of(InstanceStatus.ACTIVE, InstanceStatus.COMPLETED), null, null, null, null, 50));

        assertThat(bodies(RUNTIME))
                .containsExactly("{\"processInstanceIds\":[\"pi-1\"],\"size\":1,\"tenantId\":\"acme\"}");
    }

    @Test
    void aPageWithNoOpenRowAsksTheRuntimeQueryNothingInsteadOfSendingAnEmptyIdList() {
        fleet(null);
        wm.stubFor(post(urlPathEqualTo(HISTORIC)).willReturn(okJson(page(1, ENDED_ROW))));

        service.search(req(List.of(InstanceStatus.ACTIVE, InstanceStatus.COMPLETED), null, null, null, null, 50));

        assertThat(bodies(HISTORIC)).hasSize(1);
        assertThat(bodies(RUNTIME)).isEmpty();
    }

    @Test
    void anEngineThatIgnoresRuntimeProcessInstanceIdsGetsTheSameBodyThenOneGetPerId() {
        // The 6.3 cliff: unknown query fields are dropped, the answer is UNFILTERED. The body is
        // the same on every engine version — the cliff is detected from the answer.
        fleet(null);
        wm.stubFor(post(urlPathEqualTo(HISTORIC)).willReturn(okJson(page(1, OPEN_ROW))));
        wm.stubFor(post(urlPathEqualTo(RUNTIME)).willReturn(okJson(page(7, "{\"id\":\"foreign-9\"}"))));

        service.search(req(List.of(InstanceStatus.ACTIVE, InstanceStatus.COMPLETED), null, null, null, null, 50));

        assertThat(bodies(RUNTIME)).containsExactly("{\"processInstanceIds\":[\"pi-1\"],\"size\":1}");
        assertThat(urls("/runtime/process-instances/pi-1")).containsExactly("/runtime/process-instances/pi-1");
    }

    @Test
    void theFailureLanePlanHydratesByIdSetOnTopOfTheSharedFiltersAndPagesTheDeadLetterScan() {
        fleet("acme");
        wm.stubFor(get(urlPathEqualTo("/management/deadletter-jobs"))
                .willReturn(okJson(page(
                        3,
                        "{\"id\":\"job-1\",\"processInstanceId\":\"pi-1\",\"createTime\":\"2026-11-10T08:30:00.000Z\","
                                + "\"exceptionMessage\":\"boom\"}"))));
        wm.stubFor(post(urlPathEqualTo(HISTORIC)).willReturn(okJson(page(1, OPEN_ROW))));

        service.search(req(List.of(InstanceStatus.FAILED), null, null, "ORD-1", null, 50));

        // First the parent-link batch for the roll-up, then the hydration query.
        assertThat(bodies(HISTORIC))
                .containsExactly(
                        "{\"processInstanceIds\":[\"pi-1\"],\"size\":1,\"tenantId\":\"acme\"}",
                        "{\"order\":\"desc\",\"processBusinessKey\":\"ORD-1\",\"processInstanceIds\":[\"pi-1\"],"
                                + "\"size\":50,\"sort\":\"startTime\",\"tenantId\":\"acme\"}");
        assertThat(bodies(RUNTIME))
                .containsExactly("{\"processInstanceIds\":[\"pi-1\"],\"size\":1,\"tenantId\":\"acme\"}");
        // Iron rule (ARCH §2.3): FAILED is never derived from one unpaged dead-letter fetch — the
        // lane is paged with explicit start and size until its total is reached.
        assertThat(urls("/management/deadletter-jobs"))
                .containsExactly(
                        "/management/deadletter-jobs?start=0&size=200&tenantId=acme",
                        "/management/deadletter-jobs?start=1&size=200&tenantId=acme",
                        "/management/deadletter-jobs?start=2&size=200&tenantId=acme");
    }

    /* ------------------------------ the businessKeyLike cliff ------------------------------ */

    @Test
    void businessKeyLikeIsProbedWithASizeOneCanaryThenSentWrappedOrVerbatimWhenItIsAPattern() {
        fleet("acme");

        service.search(req(List.of(InstanceStatus.ACTIVE, InstanceStatus.COMPLETED), null, null, null, "ORD", 50));
        service.search(req(List.of(InstanceStatus.ACTIVE, InstanceStatus.COMPLETED), null, null, null, "ORD-%", 50));

        String canary = "{\"processBusinessKeyLike\":\"%__inspector_bkl_canary__%\",\"size\":1,\"tenantId\":\"acme\"}";
        assertThat(bodies(HISTORIC))
                .containsExactly(
                        canary,
                        "{\"order\":\"desc\",\"processBusinessKeyLike\":\"%ORD%\",\"size\":50,\"sort\":\"startTime\","
                                + "\"tenantId\":\"acme\"}",
                        canary,
                        "{\"order\":\"desc\",\"processBusinessKeyLike\":\"ORD-%\",\"size\":50,\"sort\":\"startTime\","
                                + "\"tenantId\":\"acme\"}");
    }

    @Test
    void anEngineThatIgnoresBusinessKeyLikeGetsTheCanaryAndNoSearchBodyAtAll() {
        // The 6.3 cliff: the canary cannot match, so any row back proves the filter was dropped.
        fleet(null);
        wm.stubFor(post(urlPathEqualTo(HISTORIC)).willReturn(okJson(page(3, ENDED_ROW))));

        SearchResponse response = service.search(
                req(List.of(InstanceStatus.ACTIVE, InstanceStatus.COMPLETED), null, null, null, "ORD", 50));

        assertThat(bodies(HISTORIC))
                .containsExactly("{\"processBusinessKeyLike\":\"%__inspector_bkl_canary__%\",\"size\":1}");
        assertThat(response.perEngine().get("eng").ok()).isFalse();
        assertThat(response.perEngine().get("eng").error()).contains("businessKeyLike is not supported");
    }

    /* ------------------------------ fixtures ------------------------------ */

    private void fleet(String tenantId) {
        EngineConfig engine = TestEngines.builder("eng", wm.baseUrl())
                .auth(TestEngines.basicAuth("rest-admin", "ENGINE_T_PASSWORD"))
                .timeouts(new Timeouts(2000, 2000, null))
                .tenantId(tenantId)
                .build();
        when(registry.all()).thenReturn(List.of(engine));
        when(registry.resolve("eng")).thenReturn(Optional.of(engine));
    }

    private static SearchRequest req(
            List<InstanceStatus> statuses,
            String definitionKey,
            Integer definitionVersion,
            String businessKey,
            String businessKeyLike,
            Integer pageSize) {
        return new SearchRequest(
                null,
                statuses,
                definitionKey,
                definitionVersion,
                businessKey,
                businessKeyLike,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                pageSize,
                null,
                null);
    }

    private static String page(int total, String... rows) {
        return "{\"data\":[" + String.join(",", rows) + "],\"total\":" + total + ",\"start\":0,\"size\":" + rows.length
                + "}";
    }

    /** The canonical bodies POSTed to {@code path}, oldest first. */
    private List<String> bodies(String path) {
        List<String> out = new ArrayList<>();
        for (LoggedRequest request : requestsTo(path)) {
            out.add(WireJson.canonical(request.getBodyAsString()));
        }
        return out;
    }

    /** The full URLs (path and query) requested under {@code path}, oldest first. */
    private List<String> urls(String path) {
        return requestsTo(path).stream().map(LoggedRequest::getUrl).toList();
    }

    /** Requests under {@code path}, oldest first (WireMock lists the newest first). */
    private List<LoggedRequest> requestsTo(String path) {
        List<ServeEvent> newestFirst = wm.getAllServeEvents();
        return newestFirst.reversed().stream()
                .map(ServeEvent::getRequest)
                .filter(r -> r.getUrl().equals(path) || r.getUrl().startsWith(path + "?"))
                .toList();
    }
}
