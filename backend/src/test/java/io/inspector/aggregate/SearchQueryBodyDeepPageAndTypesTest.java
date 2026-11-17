package io.inspector.aggregate;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
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
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import io.inspector.support.WireJson;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Review of #720 (queries): the historic body of a deep-paging cursor page and a variable filter of
 * every value type, read off the wire as canonical JSON. Expectations were recorded against the map
 * bodies of {@code main} and must not change with the typed record.
 */
class SearchQueryBodyDeepPageAndTypesTest {

    private static final String HISTORIC = "/query/historic-process-instances";

    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final ProtectedInstanceRepository protectedRepo = mock(ProtectedInstanceRepository.class);
    private WireMockServer wm;
    private SearchService service;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        wm.stubFor(any(anyUrl()).willReturn(okJson("{\"data\":[],\"total\":0,\"start\":0,\"size\":0}")));
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
        EngineConfig engine = TestEngines.builder("eng", wm.baseUrl())
                .auth(TestEngines.basicAuth("rest-admin", "ENGINE_T_PASSWORD"))
                .timeouts(new Timeouts(2000, 2000, null))
                .tenantId("acme")
                .build();
        when(registry.all()).thenReturn(List.of(engine));
        when(registry.resolve("eng")).thenReturn(Optional.of(engine));
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        wm.stop();
    }

    @Test
    void aDeepPageWithoutACursorStartsAtZeroAndACursorPageSendsItsOffsetAsStart() {
        SearchRequest req = req(List.of(InstanceStatus.ACTIVE, InstanceStatus.COMPLETED), null);
        PagingCursor cursor = new PagingCursor(
                PagingCursor.VERSION,
                123L,
                PagingCursor.filterHash(req),
                "startTime",
                "desc",
                Map.of("eng", 40),
                null,
                List.of());

        service.deepPage(req, null, 123L);
        service.deepPage(req, cursor.encode(), 123L);

        assertThat(bodies())
                .containsExactly(
                        "{\"order\":\"desc\",\"size\":40,\"sort\":\"startTime\",\"tenantId\":\"acme\"}",
                        "{\"order\":\"desc\",\"size\":40,\"sort\":\"startTime\",\"start\":40,\"tenantId\":\"acme\"}");
    }

    @Test
    void aVariableFilterOfEveryValueTypeKeepsItsJsonType() {
        service.search(req(
                List.of(InstanceStatus.COMPLETED),
                List.of(
                        new VariableFilter("count", 7, "greaterThanOrEquals", "integer"),
                        new VariableFilter("rate", 1.5, "lessThan", "double"),
                        new VariableFilter("approved", false, "equals", "boolean"),
                        new VariableFilter("due", "2026-11-01T00:00:00Z", "greaterThan", "date"),
                        new VariableFilter("name", "%acme%", "like", "string"),
                        new VariableFilter("flag", true, null, null))));

        assertThat(bodies())
                .containsExactly("{\"finished\":true,\"order\":\"desc\",\"size\":40,\"sort\":\"startTime\","
                        + "\"tenantId\":\"acme\",\"variables\":["
                        + "{\"name\":\"count\",\"operation\":\"greaterThanOrEquals\",\"type\":\"integer\",\"value\":7},"
                        + "{\"name\":\"rate\",\"operation\":\"lessThan\",\"type\":\"double\",\"value\":1.5},"
                        + "{\"name\":\"approved\",\"operation\":\"equals\",\"type\":\"boolean\",\"value\":false},"
                        + "{\"name\":\"due\",\"operation\":\"greaterThan\",\"type\":\"date\","
                        + "\"value\":\"2026-11-01T00:00:00Z\"},"
                        + "{\"name\":\"name\",\"operation\":\"like\",\"type\":\"string\",\"value\":\"%acme%\"},"
                        + "{\"name\":\"flag\",\"operation\":\"equals\",\"value\":true}]}");
    }

    private static SearchRequest req(List<InstanceStatus> statuses, List<VariableFilter> variables) {
        return new SearchRequest(
                null, statuses, null, null, null, null, null, null, null, null, null, null, null, variables, null, 40,
                null, null);
    }

    private List<String> bodies() {
        return wm.getAllServeEvents().reversed().stream()
                .map(ServeEvent::getRequest)
                .filter(r -> r.getUrl().equals(HISTORIC))
                .map(r -> WireJson.canonical(r.getBodyAsString()))
                .toList();
    }
}
