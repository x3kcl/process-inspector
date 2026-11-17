package io.inspector.client;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.audit.AuditService;
import io.inspector.audit.ProtectedDefinitionRepository;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.catalog.DefinitionCatalogService;
import io.inspector.catalog.DeploymentCatalogService;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.Timeouts;
import io.inspector.detail.InstanceDetailService;
import io.inspector.migration.CohortMigrationService;
import io.inspector.migration.CohortPreviewRequest;
import io.inspector.migration.DefinitionVersionService;
import io.inspector.registry.EngineHealth;
import io.inspector.registry.EngineRegistry;
import io.inspector.resolve.ResolveService;
import io.inspector.sibling.SiblingDiffService;
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
 * Characterization of the query bodies of the SMALL call sites (#720) — the migration cohort
 * preview with a variable filter, the running-instance counts of the version list and the two
 * catalogs, the nearest-sibling scan, the business-key resolve and the hierarchy child walk —
 * each driven through its service and read off the wire of a WireMock engine as canonical JSON
 * ({@link WireJson}). The search plan and the Stage-0 probes have their own classes
 * ({@code SearchQueryBodyTest}, {@code StageZeroQueryBodyTest}).
 */
class QueryCallSiteBodyTest {

    private static final String HISTORIC = "/query/historic-process-instances";
    private static final String RUNTIME = "/query/process-instances";
    private static final String DEFINITION = "{\"id\":\"demo:1:x\",\"key\":\"demo\",\"version\":1}";
    private static final String ONE_DEFINITION = "{\"data\":[" + DEFINITION + "],\"total\":1,\"start\":0,\"size\":1}";
    private static final String COUNT = "{\"data\":[],\"total\":4,\"start\":0,\"size\":0}";

    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final ProtectedDefinitionRepository protectedDefinitions = mock(ProtectedDefinitionRepository.class);
    private final ProtectedInstanceRepository protectedInstances = mock(ProtectedInstanceRepository.class);
    private WireMockServer wm;
    private ProcessApiClient client;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        wm.stubFor(any(anyUrl()).atPriority(10).willReturn(notFound()));
        wm.stubFor(post(urlPathMatching("/query/.*")).willReturn(okJson(COUNT)));
        wm.stubFor(get(urlPathEqualTo("/repository/process-definitions")).willReturn(okJson(ONE_DEFINITION)));
        wm.stubFor(get(urlPathMatching("/repository/process-definitions/.+")).willReturn(okJson(DEFINITION)));
        wm.stubFor(get(urlPathMatching("/management/.*"))
                .willReturn(okJson("{\"data\":[],\"total\":0,\"start\":0,\"size\":0}")));
        client = new ProcessApiClient(new GuardedCaller(
                new MockEnvironment().withProperty("ENGINE_T_PASSWORD", "test"),
                CircuitBreakerRegistry.of(Map.of("engine", CircuitBreakerConfig.ofDefaults())),
                BulkheadRegistry.of(Map.of("engine", BulkheadConfig.ofDefaults())),
                new SimpleMeterRegistry(),
                new RecentEngineErrors(Clock.systemUTC())));
        when(protectedDefinitions.findById(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        when(protectedInstances.findById(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void theCohortPreviewWithAVariableFilterSendsTheSourceDefinitionTheCapAndOneEqualsFilter() {
        fleet(null);

        new CohortMigrationService(registry, client, protectedDefinitions, protectedInstances)
                .preview(
                        "eng",
                        new CohortPreviewRequest(
                                "demo:1:x", "demo:2:y", null, false, null, null, "region", " EU ", null));

        assertThat(bodies(RUNTIME))
                .containsExactly("{\"processDefinitionId\":\"demo:1:x\",\"size\":500,"
                        + "\"variables\":[{\"name\":\"region\",\"operation\":\"equals\",\"value\":\"EU\"}]}");
    }

    @Test
    void theVersionListCountsRunningInstancesPerDefinitionWithASizeOneProbe() {
        fleet(null);

        new DefinitionVersionService(registry, client, protectedDefinitions).versions("eng", "demo");

        assertThat(bodies(RUNTIME)).containsExactly("{\"processDefinitionId\":\"demo:1:x\",\"size\":1}");
    }

    @Test
    void theDefinitionCatalogCountsRunningInstancesPerDefinitionWithASizeOneProbe() {
        fleet(null);
        InspectorProperties props = new InspectorProperties(4, 10, null, null, null, List.of());

        new DefinitionCatalogService(registry, client, mock(CmmnApiClient.class), protectedDefinitions, props)
                .list(null, null, null, true, null);

        assertThat(bodies(RUNTIME)).containsExactly("{\"processDefinitionId\":\"demo:1:x\",\"size\":1}");
    }

    @Test
    void theDeploymentDetailCountsRunningInstancesPerContainedDefinitionWithASizeOneProbe() {
        fleet(null);
        wm.stubFor(get(urlPathEqualTo("/repository/deployments/dep-1"))
                .willReturn(okJson("{\"id\":\"dep-1\",\"name\":\"app\"}")));
        InspectorProperties props = new InspectorProperties(4, 10, null, null, null, List.of());

        new DeploymentCatalogService(registry, client, mock(AuditService.class), props).detail("eng", "dep-1");

        assertThat(bodies(RUNTIME)).containsExactly("{\"processDefinitionId\":\"demo:1:x\",\"size\":1}");
    }

    @Test
    void theNearestSiblingScanAsksForFinishedInstancesOfTheSameDefinitionNewestEndFirst() {
        fleet("acme");
        InstanceDetailService detail = detail();
        wm.stubFor(get(urlPathEqualTo("/history/historic-process-instances/subject-1"))
                .willReturn(okJson("{\"id\":\"subject-1\",\"processDefinitionId\":\"demo:1:x\"}")));

        new SiblingDiffService(registry, client, detail).nearestSibling("eng", "subject-1");

        assertThat(bodies(HISTORIC))
                .containsExactly("{\"finished\":true,\"order\":\"desc\",\"processDefinitionId\":\"demo:1:x\","
                        + "\"size\":25,\"sort\":\"endTime\",\"tenantId\":\"acme\"}");
    }

    @Test
    void theBusinessKeyResolveAsksForTheExactKeyNewestFirstUnderThePreviewCap() {
        fleet("acme");

        new ResolveService(registry, client, mock(CmmnApiClient.class), detail()).resolve("ORD-1", null);

        assertThat(bodies(HISTORIC))
                .containsExactly("{\"order\":\"desc\",\"processBusinessKey\":\"ORD-1\",\"size\":25,"
                        + "\"sort\":\"startTime\",\"tenantId\":\"acme\"}");
    }

    @Test
    void theHierarchyWalkAsksForTheChildrenOfEachNodeUnderTheBreadthCap() {
        fleet("acme");
        wm.stubFor(get(urlPathEqualTo("/history/historic-process-instances/root-1"))
                .willReturn(okJson("{\"id\":\"root-1\",\"processDefinitionId\":\"demo:1:x\"}")));

        detail().hierarchy("eng", "root-1");

        assertThat(bodies(HISTORIC))
                .containsExactly("{\"size\":50,\"superProcessInstanceId\":\"root-1\",\"tenantId\":\"acme\"}");
    }

    /* ------------------------------ fixtures ------------------------------ */

    private void fleet(String tenantId) {
        EngineConfig engine = TestEngines.builder("eng", wm.baseUrl())
                .auth(TestEngines.basicAuth("rest-admin", "ENGINE_T_PASSWORD"))
                .timeouts(new Timeouts(2000, 2000, null))
                .tenantId(tenantId)
                .build();
        when(registry.all()).thenReturn(List.of(engine));
        when(registry.require("eng")).thenReturn(engine);
        when(registry.resolve("eng")).thenReturn(Optional.of(engine));
        when(registry.resolveOrNotFound("eng")).thenReturn(engine);
        when(registry.healthOf(anyString())).thenReturn(EngineHealth.unknown());
    }

    private InstanceDetailService detail() {
        return new InstanceDetailService(
                registry,
                client,
                mock(ExternalJobApiClient.class),
                new InspectorProperties(4, 10, null, null, null, List.of()),
                protectedInstances);
    }

    /** The canonical bodies POSTed to {@code path}, oldest first (WireMock lists the newest first). */
    private List<String> bodies(String path) {
        List<ServeEvent> newestFirst = wm.getAllServeEvents();
        return newestFirst.reversed().stream()
                .map(ServeEvent::getRequest)
                .filter(r -> r.getUrl().equals(path))
                .map(r -> WireJson.canonical(r.getBodyAsString()))
                .toList();
    }
}
