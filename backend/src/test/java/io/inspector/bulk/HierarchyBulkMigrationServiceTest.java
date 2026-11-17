package io.inspector.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.action.GuardRefusedException;
import io.inspector.action.TicketPolicy;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.migration.HierarchyPlanService;
import io.inspector.migration.HierarchyPlanService.Execution;
import io.inspector.migration.HierarchyPlanService.Item;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import io.inspector.support.TestEngines;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

/** Rung 1: the hierarchy-migrate bulk door (#497) — scope and rails, never dispatch. */
@ExtendWith(MockitoExtension.class)
class HierarchyBulkMigrationServiceTest {

    private static final String ANALYSIS = "7d3c6a0e-0000-4000-8000-000000000497";

    @Mock
    BulkJobService bulk;

    @Mock
    HierarchyPlanService plans;

    @Mock
    RbacAuthorizer rbac;

    @Mock
    TicketPolicy tickets;

    @Mock
    Authentication auth;

    private final UUID devPlan = UUID.randomUUID();
    private final UUID prodPlan = UUID.randomUUID();
    private HierarchyBulkMigrationService service;

    @BeforeEach
    void setup() {
        EngineRegistry registry = new EngineRegistry(new InspectorProperties(
                null,
                null,
                null,
                null,
                List.of(
                        TestEngines.engine("engine-a", "http://a", EngineEnvironment.DEV, EngineMode.READ_WRITE),
                        TestEngines.engine("engine-p", "http://p", EngineEnvironment.PROD, EngineMode.READ_WRITE))));
        service = new HierarchyBulkMigrationService(bulk, plans, registry, rbac, tickets);
        lenient().when(rbac.hasRoleOn(any(), eq(Role.ADMIN), anyString())).thenReturn(true);
        lenient().when(plans.engineOf(devPlan)).thenReturn("engine-a");
        lenient().when(plans.engineOf(prodPlan)).thenReturn("engine-p");
        lenient().when(plans.execution(devPlan)).thenReturn(execution(devPlan, "engine-a"));
        lenient().when(plans.execution(prodPlan)).thenReturn(execution(prodPlan, "engine-p"));
    }

    /** b (blocked by the analysis, listed unsent) under a under root: two executable, three items. */
    private static Execution execution(UUID planId, String engineId) {
        return new Execution(
                planId,
                engineId,
                List.of(
                        new Item("b", ANALYSIS, 2, "a", "not sent — the analysis blocked this instance."),
                        new Item("a", ANALYSIS, 1, "root", null),
                        new Item("root", ANALYSIS, 0, null, null)),
                2,
                Map.of("hierarchyPlanId", planId.toString(), "order", "children-first"));
    }

    private static BulkDtos.BulkMigrateHierarchyRequest request(UUID planId, Integer confirmedCount) {
        return new BulkDtos.BulkMigrateHierarchyRequest(
                planId, " move the tree off the bad deploy ", null, confirmedCount);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPlanBecomesOneMigrateJobWithEveryItemPlacedInTheTree() {
        service.submit(request(devPlan, null), auth);

        ArgumentCaptor<BulkDtos.BulkSubmitRequest> sent = ArgumentCaptor.forClass(BulkDtos.BulkSubmitRequest.class);
        ArgumentCaptor<Map<String, Object>> envelope = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, HierarchyOrder.Placement>> placements = ArgumentCaptor.forClass(Map.class);
        verify(bulk).submitMigration(sent.capture(), eq(auth), envelope.capture(), anyString(), placements.capture());
        assertThat(sent.getValue().verb()).isEqualTo("migrate-instance");
        assertThat(sent.getValue().reason()).isEqualTo("move the tree off the bad deploy");
        assertThat(sent.getValue().items())
                .containsExactly(
                        new BulkDtos.BulkTarget("engine-a", "b", ANALYSIS),
                        new BulkDtos.BulkTarget("engine-a", "a", ANALYSIS),
                        new BulkDtos.BulkTarget("engine-a", "root", ANALYSIS));
        assertThat(placements.getValue())
                .containsEntry(
                        "engine-a:b",
                        new HierarchyOrder.Placement(2, "a", "not sent — the analysis blocked this instance."))
                .containsEntry("engine-a:a", new HierarchyOrder.Placement(1, "root", null))
                .containsEntry("engine-a:root", new HierarchyOrder.Placement(0, null, null));
        Map<String, Object> migration =
                (Map<String, Object>) envelope.getValue().get("migration");
        assertThat(migration)
                .containsEntry("hierarchyPlanId", devPlan.toString())
                .containsEntry("order", "children-first")
                .containsEntry("executable", 2)
                .containsEntry("notSent", 1)
                .containsEntry("prodInScope", false);
    }

    @Test
    void prodNeedsTheTypedCountOfTheExecutableSet() {
        assertThatThrownBy(() -> service.submit(request(prodPlan, null), auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo("bulk-migrate-confirm-count-required"));
        assertThatThrownBy(() -> service.submit(request(prodPlan, 3), auth))
                .isInstanceOf(BulkCountDriftException.class);
        verify(bulk, never()).submitMigration(any(), any(), any(), any(), any());

        service.submit(request(prodPlan, 2), auth);
        verify(bulk).submitMigration(any(), eq(auth), anyMap(), anyString(), any());
    }

    @Test
    void refusesWithoutAdminOnThePlansEngineAndWithoutAPlan() {
        when(rbac.hasRoleOn(auth, Role.ADMIN, "engine-a")).thenReturn(false);
        assertThatThrownBy(() -> service.submit(request(devPlan, null), auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("rbac-denied"));
        // The role check comes before the plan is resolved: its refusals name definitions and instances.
        verify(plans, never()).execution(any());
        assertThatThrownBy(() -> service.submit(request(null, null), auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("hierarchy-plan-required"));
        verify(bulk, never()).submitMigration(any(), any(), any(), any(), any());
    }
}
