package io.inspector.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.action.GuardRefusedException;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditService;
import io.inspector.client.FlowablePage;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class DeploymentCatalogServiceTest {

    @Mock
    EngineRegistry registry;

    @Mock
    ProcessApiClient processes;

    @Mock
    AuditService audit;

    @Mock
    InspectorProperties props;

    @Mock
    Authentication auth;

    @Mock
    AuditEntry entry;

    private DeploymentCatalogService service;

    @BeforeEach
    void service() {
        when(props.fanoutParallelism()).thenReturn(2);
        service = new DeploymentCatalogService(registry, processes, audit, props);
    }

    @AfterEach
    void shutdown() {
        service.shutdown();
    }

    @Test
    void refusesDeleteWhenRunningInstancesExistAndCascadeIsOff() {
        EngineConfig engine = TestEngines.engine("engine-a", "http://a", EngineEnvironment.DEV, EngineMode.READ_WRITE);
        when(registry.require("engine-a")).thenReturn(engine);
        when(processes.getDeployment(eq(engine), any(), eq("dep-1"))).thenReturn(Map.of("id", "dep-1", "name", "app"));
        when(processes.listProcessDefinitions(
                        eq(engine),
                        any(),
                        nullable(String.class),
                        nullable(String.class),
                        nullable(Boolean.class),
                        eq("dep-1"),
                        anyInt(),
                        anyInt()))
                .thenReturn(new FlowablePage(List.of(Map.of("id", "alpha:1", "key", "alpha", "version", 1)), 1, 0, 1));
        when(processes.queryRuntimeProcessInstances(eq(engine), any(), any()))
                .thenReturn(new FlowablePage(List.of(), 3, 0, 1));

        assertThatThrownBy(() -> service.delete(
                        "engine-a",
                        "dep-1",
                        new DeleteDeploymentRequest("bad deploy, remove it", null, null, false),
                        auth))
                .isInstanceOf(GuardRefusedException.class)
                .hasMessageContaining("3 running");
        verify(processes, never()).deleteDeployment(any(), any(), any(), anyBoolean());
        verify(audit, never()).beginPending(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void deletesAnEmptyDeploymentAndAuditsIt() {
        EngineConfig engine = TestEngines.engine("engine-a", "http://a", EngineEnvironment.DEV, EngineMode.READ_WRITE);
        when(registry.require("engine-a")).thenReturn(engine);
        when(auth.getName()).thenReturn("ada");
        when(processes.getDeployment(eq(engine), any(), eq("dep-1"))).thenReturn(Map.of("id", "dep-1"));
        when(processes.listProcessDefinitions(
                        eq(engine),
                        any(),
                        nullable(String.class),
                        nullable(String.class),
                        nullable(Boolean.class),
                        eq("dep-1"),
                        anyInt(),
                        anyInt()))
                .thenReturn(new FlowablePage(List.of(), 0, 0, 0));
        when(audit.beginPending(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entry);

        DeleteDeploymentResponse response = service.delete(
                "engine-a",
                "dep-1",
                new DeleteDeploymentRequest("empty deployment, safe to drop", "T-1", null, null),
                auth);

        assertThat(response.outcome()).isEqualTo("ok");
        assertThat(response.cascade()).isFalse();
        verify(processes)
                .deleteDeployment(engine, io.inspector.client.GuardedCaller.CallPriority.INTERACTIVE, "dep-1", false);
    }

    @Test
    void prodRequiresTheDeploymentIdAsTheToken() {
        EngineConfig engine = TestEngines.engine("engine-a", "http://a", EngineEnvironment.PROD, EngineMode.READ_WRITE);
        when(registry.require("engine-a")).thenReturn(engine);

        assertThatThrownBy(() -> service.delete(
                        "engine-a",
                        "dep-9",
                        new DeleteDeploymentRequest("remove this empty prod deployment", null, "nope", false),
                        auth))
                .isInstanceOf(GuardRefusedException.class)
                .hasMessageContaining("dep-9");
        verify(processes, never()).deleteDeployment(any(), any(), any(), anyBoolean());
    }
}
