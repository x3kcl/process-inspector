package io.inspector.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.action.GuardRefusedException;
import io.inspector.audit.ProtectedDefinitionRepository;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.FlowablePage;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CohortMigrationServiceTest {

    @Mock
    EngineRegistry registry;

    @Mock
    ProcessApiClient processes;

    @Mock
    ProtectedDefinitionRepository protectedDefinitions;

    @Mock
    ProtectedInstanceRepository protectedInstances;

    private CohortMigrationService service;
    private EngineConfig engine;

    @BeforeEach
    void setup() {
        engine = TestEngines.engine("engine-a", "http://a", EngineEnvironment.DEV, EngineMode.READ_WRITE);
        when(registry.require("engine-a")).thenReturn(engine);
        service = new CohortMigrationService(registry, processes, protectedDefinitions, protectedInstances);
    }

    @Test
    void compilesExactBusinessKeyOntoTheRuntimeList() {
        when(processes.getProcessDefinition(eq(engine), any(), eq("demo:1:x"))).thenReturn(Map.of("key", "demo"));
        when(protectedDefinitions.findById(any())).thenReturn(Optional.empty());
        when(processes.listRuntimeInstances(
                        eq(engine), any(), eq("demo:1:x"), eq("BK-1"), isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(new FlowablePage(List.of(Map.of("id", "i1")), 1, 0, 1));
        when(protectedInstances.findById(any())).thenReturn(Optional.empty());

        CohortPreviewResponse response = service.preview(
                "engine-a",
                new CohortPreviewRequest("demo:1:x", "demo:2:y", "BK-1", false, null, null, null, null, null));

        assertThat(response.count()).isEqualTo(1);
        assertThat(response.truncated()).isFalse();
        assertThat(response.strategy()).isEqualTo("per-instance");
        verify(processes, never()).queryRuntimeProcessInstances(any(), any(), any());
    }

    @Test
    void refusesAVariableFilterCombinedWithABusinessKey() {
        assertThatThrownBy(() -> service.preview(
                        "engine-a",
                        new CohortPreviewRequest("demo:1:x", null, "BK", false, null, null, "status", "bad", null)))
                .isInstanceOf(GuardRefusedException.class)
                .hasMessageContaining("cannot be combined");
        verify(processes, never()).listRuntimeInstances(any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void listsInstanceIdsOnTheSameFilterAndMarksAShortPageAsALowerBound() {
        when(processes.listRuntimeInstances(
                        eq(engine), any(), eq("demo:1:x"), isNull(), isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(new FlowablePage(List.of(Map.of("id", "i1")), 4, 0, 1));

        CohortMigrationService.ListedCohort listed = service.listInstances(
                "engine-a", new CohortPreviewRequest("demo:1:x", "demo:2:y", null, null, null, null, null, null, null));

        assertThat(listed.instanceIds()).containsExactly("i1");
        assertThat(listed.total()).isEqualTo(4);
        assertThat(listed.lowerBound()).isTrue();
        assertThat(listed.fromDefinitionId()).isEqualTo("demo:1:x");
        assertThat(listed.toDefinitionId()).isEqualTo("demo:2:y");
    }
}
