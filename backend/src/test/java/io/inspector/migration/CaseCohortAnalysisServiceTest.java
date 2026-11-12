package io.inspector.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.action.ActionRequest;
import io.inspector.action.ActionResult;
import io.inspector.action.CaseMigrationExpectation;
import io.inspector.action.CorrectiveActionService;
import io.inspector.action.GuardRefusedException;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.migration.CaseCohortService.ListedCohort;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import io.inspector.support.TestEngines;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.web.client.ResourceAccessException;

/**
 * Rung 1: the case cohort analysis and its bulk bridge (#500) over mocked engine reads. The
 * real-engine proof is {@code CaseMigrationIT}.
 */
@ExtendWith(MockitoExtension.class)
class CaseCohortAnalysisServiceTest {

    private static final String ENGINE = "engine-a";
    private static final String FROM = "def-1";
    private static final String TO = "def-2";

    @Mock
    CaseCohortService cohorts;

    @Mock
    CmmnApiClient cmmn;

    @Mock
    EngineRegistry registry;

    @Mock
    MigrationAnalysisRepository analyses;

    @Mock
    RbacAuthorizer rbac;

    @Mock
    ApplicationEventPublisher events;

    @Mock
    CorrectiveActionService actions;

    @Mock
    Authentication auth;

    private final EngineConfig engine = TestEngines.engine(ENGINE, "http://engine.test/flowable-rest/service");
    private final Map<UUID, MigrationAnalysis> rows = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private CaseCohortAnalysisService service;
    private CaseCohortBulkMigration bridge;

    @BeforeEach
    void setup() {
        lenient().when(analyses.save(any())).thenAnswer(invocation -> {
            MigrationAnalysis row = invocation.getArgument(0);
            rows.put(row.getId(), row);
            return row;
        });
        lenient()
                .when(analyses.findById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.getArgument(0))));
        lenient().when(registry.require(ENGINE)).thenReturn(engine);
        lenient().when(rbac.hasRoleOn(auth, Role.ADMIN, ENGINE)).thenReturn(true);
        service = new CaseCohortAnalysisService(
                cohorts, cmmn, registry, analyses, rbac, json, Clock.systemUTC(), events, Runnable::run);
        bridge = new CaseCohortBulkMigration(analyses, actions, cmmn, registry, json);
    }

    private void models() {
        when(cmmn.getCmmnCaseDefinition(engine, CallPriority.INTERACTIVE, FROM))
                .thenReturn(Map.of("id", FROM, "key", "demoCase"));
        // The real row has no resourceName, only the resource URL (measured on 6.8.0 and 7.1.0).
        when(cmmn.getCmmnCaseDefinition(engine, CallPriority.INTERACTIVE, TO))
                .thenReturn(
                        Map.of(
                                "id",
                                TO,
                                "key",
                                "demoCase",
                                "deploymentId",
                                "dep-2",
                                "resource",
                                "http://engine.test/flowable-rest/cmmn-api/cmmn-repository/deployments/dep-2/resources/v2.cmmn.xml"));
        when(cmmn.cmmnDeploymentResourceData(engine, CallPriority.INTERACTIVE, "dep-2", "v2.cmmn.xml"))
                .thenReturn("<planItem id=\"p1\" definitionRef=\"reviewTask\"/>"
                        + "<planItem id=\"p2\" definitionRef=\"approveTask\"/>");
    }

    private void runningCase(String caseId, String definitionId, String... livePlanItemDefinitionIds) {
        when(cmmn.getCmmnCaseInstance(engine, CallPriority.INTERACTIVE, caseId))
                .thenReturn(Map.of("id", caseId, "caseDefinitionId", definitionId));
        List<Map<String, Object>> planItems = new ArrayList<>();
        for (String id : livePlanItemDefinitionIds) {
            planItems.add(Map.of("id", "inst-" + id, "planItemDefinitionId", id, "state", "active"));
        }
        lenient()
                .when(cmmn.listCmmnPlanItemInstances(
                        eq(engine),
                        eq(CallPriority.INTERACTIVE),
                        eq(Map.of("caseInstanceId", caseId)),
                        eq(0),
                        anyInt()))
                .thenReturn(new FlowablePage(planItems, planItems.size(), 0, 500));
    }

    private static CaseCohortRequest filter() {
        return new CaseCohortRequest(FROM, TO, null, null, null, null);
    }

    private CaseCohortAnalysisView analyzed(String... caseIds) {
        when(cohorts.listCases(eq(ENGINE), any()))
                .thenReturn(new ListedCohort(FROM, TO, List.of(caseIds), caseIds.length, false));
        return service.get(service.start(ENGINE, filter(), auth).id(), auth);
    }

    @Test
    void aCaseIsCleanOrABlockerAndOnlyCleanCasesAreExecutable() {
        models();
        runningCase("clean", FROM, "reviewTask");
        runningCase("blocked", FROM, "reviewTask", "optionalNote");
        runningCase("left", "def-other", "reviewTask");
        when(cmmn.getCmmnCaseInstance(engine, CallPriority.INTERACTIVE, "ended"))
                .thenReturn(null);

        CaseCohortAnalysisView view = analyzed("clean", "blocked", "left", "ended");

        assertThat(view.status()).isEqualTo("COMPLETED");
        assertThat(view.sourceCaseDefinitionId()).isEqualTo(FROM);
        assertThat(view.targetCaseDefinitionId()).isEqualTo(TO);
        assertThat(view.executableIds()).containsExactly("clean");
        assertThat(view.cleanCount()).isEqualTo(1);
        assertThat(view.blockerCount()).isEqualTo(3);
        assertThat(view.blockers())
                .extracting(CaseCohortAnalysisView.BlockedCase::caseInstanceId)
                .containsExactly("blocked", "left", "ended");
        assertThat(view.blockers().get(0).reason()).contains("optionalNote").contains("not in the target case model");
        assertThat(view.byPlanItem()).containsExactly(new CaseCohortAnalysisView.PlanItemAggregate("optionalNote", 1));
        assertThat(view.lowerBound()).isFalse();
        assertThat(view.banner()).isEqualTo(CaseCohortAnalysisView.BANNER);
        // The target model is read once for the whole cohort, not once per case.
        verify(cmmn, times(1)).cmmnDeploymentResourceData(any(), any(), any(), any());
    }

    @Test
    void theStoredRowIsACaseRowThatTheProcessReadersCanTellApart() {
        models();
        runningCase("clean", FROM, "reviewTask");

        CaseCohortAnalysisView view = analyzed("clean");

        MigrationAnalysis row = rows.get(view.id());
        assertThat(row.getTaxonomyVersion()).isEqualTo(CaseCohortAnalysisService.CASE_TAXONOMY_VERSION);
        assertThat(CaseCohortAnalysisService.isCaseAnalysis(row)).isTrue();
        MigrationAnalysis process = new MigrationAnalysis(
                UUID.randomUUID(),
                ENGINE,
                "{}",
                "demo:1:x",
                "demo:2:y",
                MigrationFinding.TAXONOMY_VERSION,
                "{}",
                "[]",
                "{}",
                Instant.EPOCH);
        rows.put(process.getId(), process);
        assertThat(CaseCohortAnalysisService.isCaseAnalysis(process)).isFalse();
        assertThatThrownBy(() -> service.get(process.getId(), auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("analysis-not-found"));
        assertThatThrownBy(() -> bridge.resolve(process.getId()))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("analysis-not-found"));
    }

    @Test
    void anEngineThatStopsAnsweringLeavesAFailedLowerBoundThatTheBulkDoorRefuses() {
        models();
        runningCase("first", FROM, "reviewTask");
        when(cmmn.getCmmnCaseInstance(engine, CallPriority.INTERACTIVE, "second"))
                .thenThrow(new ResourceAccessException("connect timed out"));

        CaseCohortAnalysisView view = analyzed("first", "second", "third");

        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.lowerBound()).isTrue();
        assertThat(view.boundLabel()).contains("estimated 1 of 3 running cases");
        verify(cmmn, never()).getCmmnCaseInstance(engine, CallPriority.INTERACTIVE, "third");
        assertThatThrownBy(() -> bridge.resolve(view.id()))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("analysis-not-completed"));
    }

    @Test
    void startRefusesACrossKeyOrSameVersionTargetAndANonAdmin() {
        when(cohorts.listCases(eq(ENGINE), any())).thenReturn(new ListedCohort(FROM, TO, List.of("c"), 1, false));
        when(cmmn.getCmmnCaseDefinition(engine, CallPriority.INTERACTIVE, FROM))
                .thenReturn(Map.of("id", FROM, "key", "demoCase"));
        when(cmmn.getCmmnCaseDefinition(engine, CallPriority.INTERACTIVE, TO))
                .thenReturn(Map.of("id", TO, "key", "anotherCase"));
        assertThatThrownBy(() -> service.start(ENGINE, filter(), auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("cross-key-target"));

        when(cohorts.listCases(eq(ENGINE), any())).thenReturn(new ListedCohort(FROM, FROM, List.of("c"), 1, false));
        assertThatThrownBy(() -> service.start(ENGINE, filter(), auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("same-definition-target"));

        when(rbac.hasRoleOn(auth, Role.ADMIN, ENGINE)).thenReturn(false);
        assertThatThrownBy(() -> service.start(ENGINE, filter(), auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("rbac-denied"));
        assertThat(rows).isEmpty();
    }

    @Test
    void theBulkItemCarriesTheAnalysisTargetAndItsCompareAndSetBaseline() {
        models();
        runningCase("clean", FROM, "reviewTask");
        runningCase("blocked", FROM, "optionalNote");
        CaseCohortAnalysisView view = analyzed("clean", "blocked");
        when(actions.executeCaseMigrationBulkItem(eq(ENGINE), eq("clean"), any(), eq(auth), any()))
                .thenReturn(new ActionResult(UUID.randomUUID(), "c", "ok", 200, "migrated"));

        bridge.dispatch(view.id().toString(), ENGINE, "clean", "move off the bad deploy", "INC-1", auth);

        ArgumentCaptor<ActionRequest> request = ArgumentCaptor.forClass(ActionRequest.class);
        ArgumentCaptor<CaseMigrationExpectation> expected = ArgumentCaptor.forClass(CaseMigrationExpectation.class);
        verify(actions)
                .executeCaseMigrationBulkItem(eq(ENGINE), eq("clean"), request.capture(), eq(auth), expected.capture());
        assertThat(request.getValue().toCaseDefinitionId()).isEqualTo(TO);
        assertThat(request.getValue().reason()).isEqualTo("move off the bad deploy");
        assertThat(request.getValue().ticketId()).isEqualTo("INC-1");
        assertThat(request.getValue().confirmToken()).isNull();
        assertThat(expected.getValue()).isEqualTo(new CaseMigrationExpectation(FROM, "reviewTask"));

        // A blocker has no baseline: it cannot be dispatched even if a job names it.
        assertThatThrownBy(() -> bridge.dispatch(view.id().toString(), ENGINE, "blocked", "reason text", null, auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("not-in-analysis"));
        assertThatThrownBy(() -> bridge.dispatch("not-a-uuid", ENGINE, "clean", "reason text", null, auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("analysis-ref-invalid"));
    }

    @Test
    void verifyNowReadsTheCaseDefinitionAndNeverMigrates() {
        models();
        runningCase("clean", FROM, "reviewTask");
        CaseCohortAnalysisView view = analyzed("clean");

        assertThat(bridge.verify(view.id().toString(), ENGINE, "clean").migrated())
                .isFalse();
        when(cmmn.getCmmnCaseInstance(engine, CallPriority.INTERACTIVE, "clean"))
                .thenReturn(Map.of("id", "clean", "caseDefinitionId", TO));
        assertThat(bridge.verify(view.id().toString(), ENGINE, "clean").migrated())
                .isTrue();
        when(cmmn.getCmmnCaseInstance(engine, CallPriority.INTERACTIVE, "clean"))
                .thenReturn(null);
        assertThat(bridge.verify(view.id().toString(), ENGINE, "clean").evidence())
                .contains("no longer running");
        verify(cmmn, never()).migrateCase(any(), any(), any(), any(), any(), any());
    }
}
