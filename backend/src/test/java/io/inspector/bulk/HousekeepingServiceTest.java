package io.inspector.bulk;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.inspector.audit.AuditService;
import io.inspector.audit.ProtectedDefinitionRepository;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import io.inspector.support.TestEngines;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

class HousekeepingServiceTest {

    private final ProcessApiClient flowable = mock(ProcessApiClient.class);
    private final EngineRegistry registry = new EngineRegistry(new io.inspector.config.InspectorProperties(
            null,
            null,
            null,
            null,
            List.of(TestEngines.engine("dev", "http://localhost:1", EngineEnvironment.DEV, EngineMode.READ_WRITE))));
    private final RbacAuthorizer rbac = mock(RbacAuthorizer.class);
    private final Authentication admin = new TestingAuthenticationToken("admin", "n/a", "ROLE_ADMIN");

    @Test
    void refusesAMissingEndBound() {
        HousekeepingService service = service("");
        when(rbac.hasRoleOn(any(), eq(Role.ADMIN), eq("dev"))).thenReturn(true);

        assertThatThrownBy(() -> service.preview(request(null), admin)).hasMessageContaining("ended-before");
    }

    @Test
    void dropsARunningRow() {
        HousekeepingService service = service("");
        when(rbac.hasRoleOn(any(), eq(Role.ADMIN), eq("dev"))).thenReturn(true);
        when(flowable.listFinishedProcesses(
                        any(), eq(CallPriority.HOUSEKEEPING), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new FlowablePage(
                        List.of(
                                Map.of("id", "still", "businessKey", "bk"),
                                Map.of("id", "done", "endTime", "2020-01-01T00:00:00Z")),
                        2,
                        0,
                        50));

        BulkDtos.HistoryPreview preview = service.preview(request("2020-06-01T00:00:00Z"), admin);

        org.assertj.core.api.Assertions.assertThat(preview.count()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(preview.sampleIds()).containsExactly("done");
    }

    @Test
    void refusesAHeldDefinitionAndAuditsIt() {
        when(rbac.hasRoleOn(any(), eq(Role.ADMIN), eq("dev"))).thenReturn(true);
        AuditService audit = mock(AuditService.class);
        HousekeepingService service = new HousekeepingService(
                flowable,
                mock(CmmnApiClient.class),
                mock(BulkJobService.class),
                registry,
                rbac,
                mock(ProtectedDefinitionRepository.class),
                audit,
                "order",
                "");

        assertThatThrownBy(() -> service.preview(request("2020-06-01T00:00:00Z"), admin))
                .hasMessageContaining("hold");
        org.mockito.Mockito.verify(audit).recordConfigEvent(eq("history-hold-refused"), eq("admin"), eq(false), any());
    }

    private HousekeepingService service(String held) {
        return new HousekeepingService(
                flowable,
                mock(CmmnApiClient.class),
                mock(BulkJobService.class),
                registry,
                rbac,
                mock(ProtectedDefinitionRepository.class),
                mock(AuditService.class),
                held,
                "");
    }

    private static BulkDtos.HistoryDeleteRequest request(String finishedBefore) {
        return new BulkDtos.HistoryDeleteRequest(
                "bpmn", List.of("dev"), "order", null, null, finishedBefore, null, null, null);
    }
}
