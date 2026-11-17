package io.inspector.surgery;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditService;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.GuardedCaller;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessInstanceRecords;
import io.inspector.client.RecentEngineErrors;
import io.inspector.client.RepositoryRecords;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineHealth;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.support.TestEngines;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.TestingAuthenticationToken;

/**
 * #718: Flowable 6.3.1 sends {@code scope: null} and no {@code executionId} on historic variable
 * rows, so the row cannot tell an execution-local variable from a process variable. 6.3 DOES honor
 * the {@code executionId} filter of {@code GET /history/historic-variable-instances} (live-probed on
 * 6.3.1, 6.8 and 7.1): process-scope rows are exactly those on the process-instance execution.
 * The fixtures are one recorded 6.3.1 instance — {@code procVar} set at start, {@code locVar}
 * set {@code scope=local} on the user-task execution — read without and with that filter.
 */
class RestartLegacyVariableScopeTest {

    private static final String ENGINE = "legacy-engine";
    private static final String PI = "4a868325-e256-11f1-94cc-2a5887c4fb7a";
    private static final String HISTORIC_VARIABLES = "/history/historic-variable-instances";

    private WireMockServer wm;
    private ProcessApiClient client;
    private FlowSurgeryService service;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        client = spy(new ProcessApiClient(new GuardedCaller(
                new MockEnvironment(),
                CircuitBreakerRegistry.of(Map.of("engine", CircuitBreakerConfig.ofDefaults())),
                BulkheadRegistry.of(Map.of("engine", BulkheadConfig.ofDefaults())),
                new SimpleMeterRegistry(),
                new RecentEngineErrors(Clock.systemUTC()))));
        EngineRegistry registry = new EngineRegistry(new InspectorProperties(
                null,
                null,
                null,
                null,
                List.of(TestEngines.engine(ENGINE, wm.baseUrl(), EngineEnvironment.DEV, EngineMode.READ_WRITE))));
        registry.updateHealth(
                ENGINE,
                new EngineHealth(
                        true, "6.3.1", null, 0, EngineCapabilities.fromVersion("6.3.1", true), null, null, null));

        AuditService audit = mock(AuditService.class);
        RbacAuthorizer rbac = mock(RbacAuthorizer.class);
        ProtectedInstanceRepository protectedInstances = mock(ProtectedInstanceRepository.class);
        io.inspector.audit.ProtectedDefinitionRepository protectedDefinitions =
                mock(io.inspector.audit.ProtectedDefinitionRepository.class);
        when(rbac.hasRoleOn(any(), any(), anyString())).thenReturn(true);
        when(protectedInstances.findById(any())).thenReturn(Optional.empty());
        when(protectedDefinitions.findById(any())).thenReturn(Optional.empty());
        when(audit.beginPending(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AuditEntry(
                        UUID.nameUUIDFromBytes("audit".getBytes(StandardCharsets.UTF_8)),
                        "corr-1",
                        "op",
                        Instant.parse("2026-11-17T07:15:00Z"),
                        ENGINE,
                        null,
                        PI,
                        FlowSurgeryService.RESTART_ACTION,
                        null,
                        null,
                        null,
                        false));
        service = new FlowSurgeryService(
                registry,
                client,
                new BpmnStructureService(client),
                audit,
                rbac,
                new io.inspector.audit.ProtectionGuard(protectedInstances, protectedDefinitions, rbac),
                new io.inspector.action.TicketPolicy(new io.inspector.config.AuditProperties(null, null, null)));

        // the dead instance and its definition — not the subject of this test
        doReturn(null).when(client).getRuntimeProcessInstance(any(), eq(CallPriority.INTERACTIVE), eq(PI));
        Map<String, Object> historic = new HashMap<>();
        historic.put("id", PI);
        historic.put("processDefinitionId", "pi718Scope:1:d1");
        historic.put("endTime", "2026-11-17T07:14:04.042Z");
        doReturn(ProcessInstanceRecords.instance(historic))
                .when(client)
                .getHistoricProcessInstance(any(), eq(CallPriority.INTERACTIVE), eq(PI));
        doReturn(RepositoryRecords.definitionPage(List.of(Map.of("id", "pi718Scope:1:d1"))))
                .when(client)
                .listProcessDefinitionsByKey(
                        any(), eq(CallPriority.INTERACTIVE), eq("pi718Scope"), any(), anyInt(), eq(1));
        doReturn(ProcessInstanceRecords.instance(Map.of("id", "pi-new", "processDefinitionId", "pi718Scope:1:d1")))
                .when(client)
                .startProcessInstance(any(), any(), any());
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = RestartLegacyVariableScopeTest.class.getResourceAsStream("/wire/6.3/" + name)) {
            if (in == null) throw new IOException("missing fixture wire/6.3/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void restartFromA63InstanceCarriesOnlyTheProcessScopeVariables() throws IOException {
        // Given: the unfiltered read holds both rows (scope null on each), the executionId=PI read
        // holds only the process-scope one
        wm.stubFor(get(urlPathEqualTo(HISTORIC_VARIABLES))
                .atPriority(5)
                .willReturn(okJson(fixture("historic-variable-instances-scoped-all.json"))));
        wm.stubFor(get(urlPathEqualTo(HISTORIC_VARIABLES))
                .withQueryParam("executionId", equalTo(PI))
                .atPriority(1)
                .willReturn(okJson(fixture("historic-variable-instances-scoped-process.json"))));

        // When
        RestartInstanceResult result = service.restartAsNew(
                ENGINE,
                PI,
                new RestartInstanceRequest("re-run the order per INC-718", null, false),
                new TestingAuthenticationToken("op", "n/a", "ROLE_OPERATOR"));

        // Then: locVar (execution-local) is neither carried nor reported as skipped instance state
        ArgumentCaptor<Map<String, Object>> startBody = ArgumentCaptor.captor();
        verify(client).startProcessInstance(any(), any(), startBody.capture());
        assertThat(startBody.getValue())
                .containsEntry("variables", List.of(Map.of("name", "procVar", "type", "string", "value", "p")));
        assertThat(result.carriedVariables()).containsExactly("procVar");
        assertThat(result.skippedVariables()).doesNotContainKey("locVar");
    }
}
