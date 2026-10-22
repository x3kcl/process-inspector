package io.inspector.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.audit.ProtectedDefinitionRepository;
import io.inspector.catalog.DefinitionCatalogResponse.CatalogDefinition;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineHealth;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefinitionCatalogServiceTest {

    @Mock
    EngineRegistry registry;

    @Mock
    ProcessApiClient processes;

    @Mock
    CmmnApiClient cases;

    @Mock
    ProtectedDefinitionRepository protectedDefinitions;

    @Mock
    InspectorProperties props;

    private DefinitionCatalogService service;

    @AfterEach
    void shutdown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void mergesInKeyThenVersionDescThenEngineOrder() {
        EngineConfig a = TestEngines.engine("engine-a", "http://a");
        EngineConfig b = TestEngines.engine("engine-b", "http://b");
        when(props.fanoutParallelism()).thenReturn(4);
        when(registry.all()).thenReturn(List.of(b, a));
        when(registry.healthOf(any())).thenReturn(EngineHealth.unknown());
        when(protectedDefinitions.findAll()).thenReturn(List.of());
        when(processes.listProcessDefinitions(
                        eq(a),
                        any(),
                        nullable(String.class),
                        nullable(String.class),
                        nullable(Boolean.class),
                        nullable(String.class),
                        anyInt(),
                        anyInt()))
                .thenReturn(page(List.of(def("alpha", 1, "alpha:1"), def("alpha", 3, "alpha:3"))));
        when(processes.listProcessDefinitions(
                        eq(b),
                        any(),
                        nullable(String.class),
                        nullable(String.class),
                        nullable(Boolean.class),
                        nullable(String.class),
                        anyInt(),
                        anyInt()))
                .thenReturn(page(List.of(def("zeta", 1, "zeta:1"))));
        when(processes.queryRuntimeProcessInstances(any(), any(), any()))
                .thenReturn(new FlowablePage(List.of(), 4, 0, 1));
        service = new DefinitionCatalogService(registry, processes, cases, protectedDefinitions, props);

        DefinitionCatalogResponse response = service.list(null, null, null, false, null);

        assertThat(response.definitions())
                .extracting(CatalogDefinition::definitionId)
                .containsExactly("alpha:3", "alpha:1", "zeta:1");
        assertThat(response.definitions()).allSatisfy(row -> {
            assertThat(row.runningCount()).isEqualTo(4);
            assertThat(row.protectedDefinition()).isFalse();
            assertThat(row.kind()).isEqualTo("BPMN");
        });
        assertThat(response.countsBounded()).isFalse();
    }

    @Test
    void secondCallUsesTheTwentySecondCache() {
        EngineConfig a = TestEngines.engine("engine-a", "http://a");
        when(props.fanoutParallelism()).thenReturn(2);
        when(registry.all()).thenReturn(List.of(a));
        when(registry.healthOf(any())).thenReturn(EngineHealth.unknown());
        when(protectedDefinitions.findAll()).thenReturn(List.of());
        when(processes.listProcessDefinitions(
                        any(),
                        any(),
                        nullable(String.class),
                        nullable(String.class),
                        nullable(Boolean.class),
                        nullable(String.class),
                        anyInt(),
                        anyInt()))
                .thenReturn(page(List.of(def("alpha", 1, "alpha:1"))));
        when(processes.queryRuntimeProcessInstances(any(), eq(CallPriority.INTERACTIVE), any()))
                .thenReturn(new FlowablePage(List.of(), 0, 0, 1));
        service = new DefinitionCatalogService(registry, processes, cases, protectedDefinitions, props);

        service.list(null, "alpha", null, true, null);
        service.list(null, "alpha", null, true, null);

        verify(processes, times(1))
                .listProcessDefinitions(
                        any(),
                        any(),
                        nullable(String.class),
                        nullable(String.class),
                        nullable(Boolean.class),
                        nullable(String.class),
                        anyInt(),
                        anyInt());
    }

    @Test
    void unreadableEngineIsLabeledAndNotCalled() {
        EngineConfig a = TestEngines.engine("engine-a", "http://a");
        EngineConfig b = TestEngines.engine("engine-b", "http://b");
        when(props.fanoutParallelism()).thenReturn(2);
        when(registry.all()).thenReturn(List.of(a, b));
        when(registry.resolve("engine-b")).thenReturn(Optional.of(b));
        when(registry.healthOf("engine-a")).thenReturn(EngineHealth.unknown());
        when(protectedDefinitions.findAll()).thenReturn(List.of());
        when(processes.listProcessDefinitions(
                        eq(a),
                        any(),
                        nullable(String.class),
                        nullable(String.class),
                        nullable(Boolean.class),
                        nullable(String.class),
                        anyInt(),
                        anyInt()))
                .thenReturn(page(List.of()));
        service = new DefinitionCatalogService(registry, processes, cases, protectedDefinitions, props);

        DefinitionCatalogResponse response =
                service.list(Set.of("engine-a", "engine-b"), null, null, false, Set.of("engine-a"));

        assertThat(response.perEngine().get("engine-b").ok()).isFalse();
        assertThat(response.perEngine().get("engine-b").error()).contains("outside your access scope");
        verify(processes, times(1))
                .listProcessDefinitions(
                        eq(a),
                        any(),
                        nullable(String.class),
                        nullable(String.class),
                        nullable(Boolean.class),
                        nullable(String.class),
                        anyInt(),
                        anyInt());
        verify(processes, times(0))
                .listProcessDefinitions(
                        eq(b),
                        any(),
                        nullable(String.class),
                        nullable(String.class),
                        nullable(Boolean.class),
                        nullable(String.class),
                        anyInt(),
                        anyInt());
    }

    private static FlowablePage page(List<Map<String, Object>> data) {
        return new FlowablePage(data, data.size(), 0, data.size());
    }

    private static Map<String, Object> def(String key, int version, String id) {
        return Map.of("id", id, "key", key, "version", version, "name", key, "suspended", false);
    }
}
