package io.inspector.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.inspector.action.GuardRefusedException;
import io.inspector.client.FlowablePage;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.detail.InstanceDetailService;
import io.inspector.migration.CohortBulkMigration.Cohort;
import io.inspector.migration.CohortFindingAggregator.BlockedInstance;
import io.inspector.migration.CohortMigrationService.ListedCohort;
import io.inspector.migration.HierarchyPlanService.Execution;
import io.inspector.migration.HierarchyPlanService.Item;
import io.inspector.migration.HierarchyPlanView.PlanGroupView;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import io.inspector.support.TestEngines;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

/** Rung 1: the hierarchy plan builder (#497) — tree, groups, submit resolution. No engine. */
@ExtendWith(MockitoExtension.class)
class HierarchyPlanServiceTest {

    private static final String ENGINE = "engine-a";
    private static final String ROOT_DEF = "hierRoot:1:r";
    private static final String A_DEF = "hierA:1:a";
    private static final String B_DEF = "hierB:1:b";

    @Mock
    ProcessApiClient client;

    @Mock
    InstanceDetailService detail;

    @Mock
    CohortMigrationService cohorts;

    @Mock
    CohortAnalysisService analysisRuns;

    @Mock
    CohortBulkMigration cohortBulk;

    @Mock
    MigrationAnalysisRepository analyses;

    @Mock
    HierarchyPlanRepository plans;

    @Mock
    HierarchyPlanGroupRepository groups;

    @Mock
    RbacAuthorizer rbac;

    @Mock
    Authentication auth;

    private final ObjectMapper json = new ObjectMapper();
    private final Map<UUID, HierarchyPlan> planStore = new HashMap<>();
    private final List<HierarchyPlanGroup> groupStore = new ArrayList<>();
    private final Map<UUID, MigrationAnalysis> analysisStore = new HashMap<>();
    private HierarchyPlanService service;

    @BeforeEach
    void setup() {
        InspectorProperties props = new InspectorProperties(
                null,
                null,
                null,
                null,
                List.of(TestEngines.engine(ENGINE, "http://a", EngineEnvironment.DEV, EngineMode.READ_WRITE)));
        service = new HierarchyPlanService(
                new EngineRegistry(props),
                client,
                detail,
                cohorts,
                analysisRuns,
                cohortBulk,
                analyses,
                plans,
                groups,
                rbac,
                props,
                json,
                Clock.fixed(Instant.parse("2026-11-12T12:00:00Z"), ZoneOffset.UTC));
        lenient().when(auth.getName()).thenReturn("admin");
        lenient().when(rbac.hasRoleOn(any(), eq(Role.ADMIN), anyString())).thenReturn(true);
        lenient().when(plans.save(any())).thenAnswer(inv -> {
            HierarchyPlan plan = inv.getArgument(0);
            planStore.put(plan.getId(), plan);
            return plan;
        });
        lenient()
                .when(plans.findById(any()))
                .thenAnswer(inv -> Optional.ofNullable(planStore.get(inv.<UUID>getArgument(0))));
        lenient().when(groups.save(any())).thenAnswer(inv -> {
            HierarchyPlanGroup row = inv.getArgument(0);
            groupStore.removeIf(existing -> existing.getPlanId().equals(row.getPlanId())
                    && existing.getDefinitionId().equals(row.getDefinitionId()));
            groupStore.add(row);
            return row;
        });
        lenient().when(groups.findById(any())).thenReturn(Optional.empty());
        lenient()
                .when(groups.findByPlanId(any()))
                .thenAnswer(inv -> groupStore.stream()
                        .filter(row -> row.getPlanId().equals(inv.<UUID>getArgument(0)))
                        .toList());
        lenient()
                .when(analyses.findById(any()))
                .thenAnswer(inv -> Optional.ofNullable(analysisStore.get(inv.<UUID>getArgument(0))));
        lenient().when(analyses.findAllById(any())).thenAnswer(inv -> {
            List<MigrationAnalysis> rows = new ArrayList<>();
            inv.<Iterable<UUID>>getArgument(0).forEach(id -> {
                if (analysisStore.containsKey(id)) {
                    rows.add(analysisStore.get(id));
                }
            });
            return rows;
        });
    }

    /* ------------------------------- fixtures ------------------------------- */

    private static Map<String, Object> row(String id, String definitionId) {
        return Map.of("id", id, "processDefinitionId", definitionId, "businessKey", "bk-" + id);
    }

    private void children(String parent, Map<String, Object>... rows) {
        lenient()
                .when(detail.runningChildren(any(), eq(parent)))
                .thenReturn(new FlowablePage(List.of(rows), rows.length, 0, rows.length));
    }

    /** root → a → b, one instance each. */
    private HierarchyPlanView threeLevelPlan() {
        when(client.getRuntimeProcessInstance(any(), any(), eq("root"))).thenReturn(row("root", ROOT_DEF));
        children("root", row("a", A_DEF));
        children("a", row("b", B_DEF));
        children("b");
        return service.create(ENGINE, new HierarchyPlanRequests.HierarchyPlanCreate("root", null), auth);
    }

    /** A stored analysis for one group, and what the bulk bridge resolves it to. */
    private UUID analysis(
            MigrationAnalysis.Status status, String from, List<String> executable, List<BlockedInstance> blockers)
            throws Exception {
        UUID id = UUID.randomUUID();
        CohortAnalysisService.Payload payload = new CohortAnalysisService.Payload(
                false,
                null,
                executable.size() + blockers.size(),
                blockers.size(),
                0,
                executable.size(),
                List.of(),
                blockers,
                executable,
                List.of(),
                null,
                Map.of());
        MigrationAnalysis row = new MigrationAnalysis(
                id, ENGINE, "{}", from, from + "-v2", 1, "{}", "[]", json.writeValueAsString(payload), Instant.EPOCH);
        row.update(status, from + "-v2", "{}", "[]", json.writeValueAsString(payload), Instant.EPOCH);
        analysisStore.put(id, row);
        lenient()
                .when(cohortBulk.resolve(id))
                .thenReturn(
                        new Cohort(id, ENGINE, from, from + "-v2", executable, blockers.size(), Map.of(), List.of()));
        return id;
    }

    private void decided(UUID planId, String definitionId, HierarchyPlanGroup.Decision decision, UUID analysisId) {
        HierarchyPlanGroup row = new HierarchyPlanGroup(planId, definitionId);
        row.decide(decision, analysisId, "admin", Instant.EPOCH);
        groupStore.add(row);
    }

    private static PlanGroupView group(HierarchyPlanView view, String definitionId) {
        return view.groups().stream()
                .filter(g -> g.definitionId().equals(definitionId))
                .findFirst()
                .orElseThrow();
    }

    /* --------------------------------- create --------------------------------- */

    @Test
    void createStoresTheRunningTreeGroupedByDefinitionAndEveryGroupStartsPending() {
        HierarchyPlanView view = threeLevelPlan();

        assertThat(view.rootInstanceIds()).containsExactly("root");
        assertThat(view.nodes())
                .extracting(HierarchyTree.PlanNode::instanceId, HierarchyTree.PlanNode::depth)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("root", 0),
                        org.assertj.core.groups.Tuple.tuple("a", 1),
                        org.assertj.core.groups.Tuple.tuple("b", 2));
        assertThat(view.groups()).extracting(PlanGroupView::definitionId).containsExactly(ROOT_DEF, A_DEF, B_DEF);
        assertThat(view.groups()).extracting(PlanGroupView::status).containsOnly("PENDING");
        assertThat(group(view, B_DEF).definitionKey()).isEqualTo("hierB");
        assertThat(group(view, B_DEF).version()).isEqualTo(1);
        assertThat(view.levels())
                .extracting(HierarchyPlanView.PlanLevelView::depth, HierarchyPlanView.PlanLevelView::instanceCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, 1),
                        org.assertj.core.groups.Tuple.tuple(1, 1),
                        org.assertj.core.groups.Tuple.tuple(2, 1));
        assertThat(view.executable()).isFalse();
        assertThat(view.notExecutableReason()).contains("Still to decide").contains("hierB v1");
        assertThat(planStore).containsKey(view.id());
        verify(client, never()).migrateInstance(any(), any(), any(), any());
    }

    @Test
    void aCohortFilterResolvesItsRootsServerSide() {
        CohortPreviewRequest filter =
                new CohortPreviewRequest(ROOT_DEF, null, "ORD-", true, null, null, null, null, null);
        when(cohorts.listInstances(ENGINE, filter))
                .thenReturn(new ListedCohort(ROOT_DEF, null, List.of("r1", "r2"), 2, false));
        children("r1", row("a1", A_DEF));
        children("r2");
        children("a1");

        HierarchyPlanView view =
                service.create(ENGINE, new HierarchyPlanRequests.HierarchyPlanCreate(null, filter), auth);

        assertThat(view.rootInstanceIds()).containsExactly("r1", "r2");
        assertThat(group(view, ROOT_DEF).instanceCount()).isEqualTo(2);
        assertThat(group(view, A_DEF).instanceCount()).isEqualTo(1);
    }

    @Test
    void createRefusesNoRootBothRootsAGoneRootAndAnOpenBreaker() {
        assertThatThrownBy(
                        () -> service.create(ENGINE, new HierarchyPlanRequests.HierarchyPlanCreate(null, null), auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("hierarchy-root-required"));
        assertThatThrownBy(() -> service.create(
                        ENGINE,
                        new HierarchyPlanRequests.HierarchyPlanCreate(
                                "root",
                                new CohortPreviewRequest(ROOT_DEF, null, null, null, null, null, null, null, null)),
                        auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("hierarchy-root-required"));

        when(client.getRuntimeProcessInstance(any(), any(), eq("gone"))).thenReturn(null);
        assertThatThrownBy(
                        () -> service.create(ENGINE, new HierarchyPlanRequests.HierarchyPlanCreate("gone", null), auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("instance-not-running"));

        when(client.getRuntimeProcessInstance(any(), any(), eq("root"))).thenReturn(row("root", ROOT_DEF));
        when(detail.runningChildren(any(), eq("root")))
                .thenThrow(CallNotPermittedException.createCallNotPermittedException(
                        io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("engine-a")));
        assertThatThrownBy(
                        () -> service.create(ENGINE, new HierarchyPlanRequests.HierarchyPlanCreate("root", null), auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("engine-unreachable"));

        // The engine answers, with an error: a named 502, not a bare 500.
        org.mockito.Mockito.reset(detail);
        when(detail.runningChildren(any(), eq("root")))
                .thenThrow(org.springframework.web.client.HttpServerErrorException.create(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        "boom",
                        org.springframework.http.HttpHeaders.EMPTY,
                        new byte[0],
                        null));
        assertThatThrownBy(
                        () -> service.create(ENGINE, new HierarchyPlanRequests.HierarchyPlanCreate("root", null), auth))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("engine-read-failed");
                    assertThat(e.getMessage()).contains("answered 500");
                });
        assertThat(planStore).isEmpty();
    }

    @Test
    void everyDoorNeedsAdminOnThePlansEngine() {
        HierarchyPlanView view = threeLevelPlan();
        when(rbac.hasRoleOn(auth, Role.ADMIN, ENGINE)).thenReturn(false);

        assertThatThrownBy(
                        () -> service.create(ENGINE, new HierarchyPlanRequests.HierarchyPlanCreate("root", null), auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("rbac-denied"));
        assertThatThrownBy(() -> service.get(view.id(), auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("rbac-denied"));
        assertThatThrownBy(() -> service.decide(
                        view.id(), new HierarchyPlanRequests.HierarchyPlanDecide(B_DEF, true, null, null), auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("rbac-denied"));
    }

    /* --------------------------------- decide --------------------------------- */

    @Test
    void configuringAGroupEstimatesExactlyItsInstancesAndSkippingRunsNothing() throws Exception {
        HierarchyPlanView view = threeLevelPlan();
        UUID running = analysis(MigrationAnalysis.Status.RUNNING, B_DEF, List.of(), List.of());
        List<MigrationMapping> mappings = List.of(MigrationMapping.oneToOne("reviewB", "approveB"));
        when(analysisRuns.startForInstances(ENGINE, B_DEF, "hierB:2:b2", mappings, List.of("b"), auth))
                .thenReturn(running);

        HierarchyPlanView configured = service.decide(
                view.id(), new HierarchyPlanRequests.HierarchyPlanDecide(B_DEF, null, "hierB:2:b2", mappings), auth);
        assertThat(group(configured, B_DEF).status()).isEqualTo("ANALYZING");
        assertThat(group(configured, B_DEF).analysisId()).isEqualTo(running);
        assertThat(configured.notExecutableReason()).contains("Still to decide");

        HierarchyPlanView skipped =
                service.decide(view.id(), new HierarchyPlanRequests.HierarchyPlanDecide(A_DEF, true, null, null), auth);
        assertThat(group(skipped, A_DEF).status()).isEqualTo("SKIPPED");
        verify(analysisRuns, never()).startForInstances(any(), eq(A_DEF), any(), any(), any(), any());

        assertThatThrownBy(() -> service.decide(
                        view.id(), new HierarchyPlanRequests.HierarchyPlanDecide("nope:1:x", true, null, null), auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("hierarchy-group-unknown"));
    }

    @Test
    void theViewCountsWhatWillBeSentAndSaysWhyAPlanIsNotReady() throws Exception {
        HierarchyPlanView view = threeLevelPlan();
        UUID id = view.id();
        decided(
                id,
                ROOT_DEF,
                HierarchyPlanGroup.Decision.CONFIGURED,
                analysis(MigrationAnalysis.Status.COMPLETED, ROOT_DEF, List.of("root"), List.of()));
        decided(
                id,
                A_DEF,
                HierarchyPlanGroup.Decision.CONFIGURED,
                analysis(MigrationAnalysis.Status.FAILED, A_DEF, List.of(), List.of()));
        decided(
                id,
                B_DEF,
                HierarchyPlanGroup.Decision.CONFIGURED,
                analysis(
                        MigrationAnalysis.Status.COMPLETED,
                        B_DEF,
                        List.of(),
                        List.of(new BlockedInstance("b", "reviewB has no target"))));

        HierarchyPlanView failed = service.get(id, auth);
        assertThat(group(failed, A_DEF).status()).isEqualTo("ANALYSIS_FAILED");
        assertThat(failed.notExecutableReason()).contains("estimate failed").contains("hierA v1");
        assertThat(group(failed, B_DEF).status()).isEqualTo("CONFIGURED");
        assertThat(group(failed, B_DEF).blockerCount()).isEqualTo(1);
        assertThat(group(failed, ROOT_DEF).targetDefinitionId()).isEqualTo(ROOT_DEF + "-v2");
        assertThat(failed.executableCount()).isEqualTo(1);
        assertThat(failed.notSentCount()).isEqualTo(1);

        groupStore.removeIf(row -> row.getDefinitionId().equals(A_DEF));
        decided(id, A_DEF, HierarchyPlanGroup.Decision.SKIPPED, null);
        HierarchyPlanView ready = service.get(id, auth);
        assertThat(ready.executable()).isTrue();
        assertThat(ready.notExecutableReason()).isNull();
        assertThat(ready.levels())
                .extracting(HierarchyPlanView.PlanLevelView::executableCount)
                .containsExactly(1, 0, 0);
    }

    /* ------------------------------ submit resolution ------------------------------ */

    @Test
    void executionOrdersDeepestFirstAndEachItemNamesItsPlannedParent() throws Exception {
        UUID id = threeLevelPlan().id();
        UUID rootAnalysis = analysis(MigrationAnalysis.Status.COMPLETED, ROOT_DEF, List.of("root"), List.of());
        UUID aAnalysis = analysis(MigrationAnalysis.Status.COMPLETED, A_DEF, List.of("a"), List.of());
        UUID bAnalysis = analysis(MigrationAnalysis.Status.COMPLETED, B_DEF, List.of("b"), List.of());
        decided(id, ROOT_DEF, HierarchyPlanGroup.Decision.CONFIGURED, rootAnalysis);
        decided(id, A_DEF, HierarchyPlanGroup.Decision.CONFIGURED, aAnalysis);
        decided(id, B_DEF, HierarchyPlanGroup.Decision.CONFIGURED, bAnalysis);

        Execution execution = service.execution(id);

        assertThat(execution.engineId()).isEqualTo(ENGINE);
        assertThat(execution.executableCount()).isEqualTo(3);
        assertThat(execution.items())
                .containsExactly(
                        new Item("b", bAnalysis.toString(), 2, "a", null),
                        new Item("a", aAnalysis.toString(), 1, "root", null),
                        new Item("root", rootAnalysis.toString(), 0, null, null));
        assertThat(execution.provenance())
                .containsEntry("hierarchyPlanId", id.toString())
                .containsEntry("order", "children-first")
                .containsEntry("levels", List.of(0, 1, 2));
    }

    @Test
    void aSkippedGroupIsTransparentAndABlockedInstanceIsListedUnsentWithItsReason() throws Exception {
        UUID id = threeLevelPlan().id();
        UUID rootAnalysis = analysis(MigrationAnalysis.Status.COMPLETED, ROOT_DEF, List.of("root"), List.of());
        UUID bAnalysis = analysis(
                MigrationAnalysis.Status.COMPLETED,
                B_DEF,
                List.of(),
                List.of(new BlockedInstance("b", "reviewB has no target")));
        decided(id, ROOT_DEF, HierarchyPlanGroup.Decision.CONFIGURED, rootAnalysis);
        decided(id, A_DEF, HierarchyPlanGroup.Decision.SKIPPED, null);
        decided(id, B_DEF, HierarchyPlanGroup.Decision.CONFIGURED, bAnalysis);

        Execution execution = service.execution(id);

        // A is not in the job; B's planned parent is the nearest item above it: the root.
        assertThat(execution.items()).extracting(Item::instanceId).containsExactly("b", "root");
        Item b = execution.items().get(0);
        assertThat(b.parentInstanceId()).isEqualTo("root");
        assertThat(b.notSentReason())
                .contains("the analysis blocked this instance")
                .contains("reviewB has no target");
        assertThat(execution.items().get(1).notSentReason()).isNull();
        assertThat(execution.executableCount()).isEqualTo(1);
    }

    @Test
    void executionRefusesAnUndecidedGroupATruncatedTreeAndAnAllBlockedPlan() throws Exception {
        UUID id = threeLevelPlan().id();
        assertThatThrownBy(() -> service.execution(id))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("hierarchy-plan-incomplete"));

        decided(id, ROOT_DEF, HierarchyPlanGroup.Decision.SKIPPED, null);
        decided(id, A_DEF, HierarchyPlanGroup.Decision.SKIPPED, null);
        decided(
                id,
                B_DEF,
                HierarchyPlanGroup.Decision.CONFIGURED,
                analysis(
                        MigrationAnalysis.Status.COMPLETED,
                        B_DEF,
                        List.of(),
                        List.of(new BlockedInstance("b", "reviewB has no target"))));
        assertThatThrownBy(() -> service.execution(id))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo("hierarchy-nothing-executable"));

        // A 10k-child multi-instance level: one read, reported, and the plan cannot be submitted.
        when(client.getRuntimeProcessInstance(any(), any(), eq("wide"))).thenReturn(row("wide", ROOT_DEF));
        List<Map<String, Object>> page = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            page.add(row("mi-" + i, B_DEF));
            children("mi-" + i);
        }
        when(detail.runningChildren(any(), eq("wide"))).thenReturn(new FlowablePage(page, 10_000, 0, 50));
        HierarchyPlanView wide =
                service.create(ENGINE, new HierarchyPlanRequests.HierarchyPlanCreate("wide", null), auth);
        assertThat(wide.executable()).isFalse();
        assertThat(wide.truncations()).containsExactly(new HierarchyTree.PlanTruncation("breadth", "wide", 10_000, 50));
        assertThat(wide.notExecutableReason()).contains("10000 running children");
        assertThatThrownBy(() -> service.execution(wide.id()))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("hierarchy-plan-truncated"));
        verify(detail, org.mockito.Mockito.times(1)).runningChildren(any(), eq("wide"));

        assertThatThrownBy(() -> service.execution(UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("hierarchy-plan-not-found"));
    }
}
