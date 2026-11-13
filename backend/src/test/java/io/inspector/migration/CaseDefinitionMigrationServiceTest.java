package io.inspector.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.inspector.action.ActionResult;
import io.inspector.action.EngineRejectedException;
import io.inspector.action.GuardRefusedException;
import io.inspector.action.TicketPolicy;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditOutcome;
import io.inspector.audit.AuditService;
import io.inspector.bulk.BulkCountDriftException;
import io.inspector.client.CaseMigrationClient;
import io.inspector.client.CaseMigrationRoute;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineHealth;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.support.TestEngines;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.client.HttpServerErrorException;

/**
 * Rung 1: the three Flowable 7.x case-migration doors (#500). The version gate is asserted to
 * refuse with no engine interaction at all; the real-engine proof is {@code CaseMigrationIT}.
 */
class CaseDefinitionMigrationServiceTest {

    private static final String ENGINE = "engine-x";
    private static final String FROM = "def-1";
    private static final String TO = "def-2";
    private static final String REASON = "move cases to the fixed model";

    private final CmmnApiClient cmmn = mock(CmmnApiClient.class);
    private final CaseMigrationClient cases = mock(CaseMigrationClient.class);
    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final RbacAuthorizer rbac = mock(RbacAuthorizer.class);
    private final AuditService audit = mock(AuditService.class);
    private final Authentication admin = new TestingAuthenticationToken("admin", "n/a", "ROLE_ADMIN");

    private EngineConfig engine = TestEngines.engine(ENGINE, "http://engine.test/flowable-rest/service");
    private CaseDefinitionMigrationService service;
    private AuditEntry pending;

    @BeforeEach
    void setUp() {
        service = new CaseDefinitionMigrationService(
                registry,
                cmmn,
                cases,
                rbac,
                audit,
                new TicketPolicy(new io.inspector.config.AuditProperties(null, null, null)),
                new io.inspector.security.reauth.DangerousActionReauthGate(
                        new io.inspector.security.OidcProperties(null, false, null), java.time.Clock.systemUTC()));
        when(registry.require(ENGINE)).thenAnswer(invocation -> engine);
        when(rbac.hasRoleOn(any(), any(), eq(ENGINE))).thenReturn(true);
        pending = new AuditEntry(
                UUID.nameUUIDFromBytes("audit".getBytes(StandardCharsets.UTF_8)),
                "corr-1",
                "admin",
                Instant.parse("2026-11-12T12:00:00Z"),
                ENGINE,
                null,
                null,
                CaseDefinitionMigrationService.BATCH_ACTION,
                null,
                null,
                null,
                false);
        lenient()
                .when(audit.beginPending(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(pending);
    }

    private void engineVersion(String version) {
        when(registry.healthOf(ENGINE))
                .thenReturn(new EngineHealth(
                        true,
                        version,
                        null,
                        0L,
                        new EngineCapabilities(true, true, true, true, true),
                        null,
                        null,
                        null));
    }

    private void prod() {
        engine = TestEngines.engine(
                ENGINE, "http://engine.test/flowable-rest/service", EngineEnvironment.PROD, EngineMode.READ_WRITE);
    }

    private void definitions(String sourceRefs, String targetRefs) {
        when(cmmn.getCmmnCaseDefinition(engine, CallPriority.INTERACTIVE, FROM))
                .thenReturn(Map.of("id", FROM, "key", "demoCase", "deploymentId", "dep-1", "resourceName", "v1.cmmn"));
        when(cmmn.getCmmnCaseDefinition(engine, CallPriority.INTERACTIVE, TO))
                .thenReturn(Map.of("id", TO, "key", "demoCase", "deploymentId", "dep-2", "resourceName", "v2.cmmn"));
        lenient()
                .when(cmmn.cmmnDeploymentResourceData(engine, CallPriority.INTERACTIVE, "dep-1", "v1.cmmn"))
                .thenReturn(refs(sourceRefs));
        lenient()
                .when(cmmn.cmmnDeploymentResourceData(engine, CallPriority.INTERACTIVE, "dep-2", "v2.cmmn"))
                .thenReturn(refs(targetRefs));
    }

    private static String refs(String commaSeparated) {
        StringBuilder xml = new StringBuilder("<case>");
        for (String id : commaSeparated.split(",")) {
            xml.append("<planItem definitionRef=\"").append(id).append("\"/>");
        }
        return xml.append("</case>").toString();
    }

    private static CaseDefinitionMigrationRequest request(Integer confirmedCount) {
        return new CaseDefinitionMigrationRequest(TO, REASON, "INC-500", confirmedCount, null);
    }

    @Test
    void aSixEightEngineRefusesAllThreeRoutesWithTheVersionGateBeforeAnyEngineContact() {
        engineVersion("6.8.0");

        for (Runnable door : List.<Runnable>of(
                () -> service.migrateBatch(ENGINE, FROM, request(null), admin),
                () -> service.migrateHistoric(ENGINE, FROM, request(null), admin),
                () -> service.migrateHistoricCase(ENGINE, "case-1", request(null), admin))) {
            assertThatThrownBy(door::run).isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                assertThat(e.code()).isEqualTo("version-gate");
                assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage())
                        .contains("Flowable 7.x version gate")
                        .contains("6.8.0")
                        .contains("Refused before any engine call");
            });
        }
        verifyNoInteractions(cmmn, cases, audit);
    }

    @Test
    void anUnknownVersionFailsClosedOnTheGate() {
        engineVersion(null);
        assertThatThrownBy(() -> service.migrateBatch(ENGINE, FROM, request(null), admin))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("version-gate"));
        verifyNoInteractions(cmmn, cases, audit);
    }

    @Test
    void batchSendsOneBatchMigrateAuditsV1AndSaysTheBatchIsNotDone() {
        engineVersion("7.1.0");
        definitions("reviewTask", "reviewTask,approveTask");
        when(cmmn.countRunningCases(engine, CallPriority.INTERACTIVE, FROM)).thenReturn(3L);
        when(cases.listCaseMigrationBatches(eq(engine), eq(CallPriority.INTERACTIVE), eq(FROM), anyInt()))
                .thenReturn(new FlowablePage(
                        List.of(
                                Map.of("id", "old", "searchKey2", TO, "createTime", "2026-11-12T10:00:00.000Z"),
                                Map.of("id", "other", "searchKey2", "def-9", "createTime", "2026-11-12T12:00:00.000Z"),
                                Map.of("id", "new", "searchKey2", TO, "createTime", "2026-11-12T11:00:00.000Z")),
                        3,
                        0,
                        20));

        ActionResult result = service.migrateBatch(ENGINE, FROM, request(null), admin);

        verify(cmmn)
                .migrateCase(
                        eq(engine),
                        eq(CallPriority.INTERACTIVE),
                        eq("7.1.0"),
                        eq(CaseMigrationRoute.BY_DEFINITION_BATCH),
                        eq(FROM),
                        eq(Map.of("toCaseDefinitionId", TO)));
        verify(audit)
                .beginPending(
                        eq("admin"),
                        eq(ENGINE),
                        any(),
                        eq(null),
                        eq(CaseDefinitionMigrationService.BATCH_ACTION),
                        eq(REASON),
                        eq("INC-500"),
                        argThat(payload -> "migrate-case/v1".equals(payload.get("schema"))
                                && "by-definition-batch".equals(payload.get("route"))
                                && FROM.equals(payload.get("fromCaseDefinitionId"))
                                && TO.equals(payload.get("toCaseDefinitionId"))
                                && Long.valueOf(3).equals(payload.get("runningCases"))),
                        any());
        verify(audit).close(pending, AuditOutcome.ok, 200, "{\"batchId\":\"new\"}", true);
        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(result.deltaStatement())
                .contains("3 running cases")
                .contains("batch new")
                .contains("no case has moved because of this answer alone");
    }

    @Test
    void batchIsNotSentWhenTheTargetModelDropsAPlanItemDefinition() {
        engineVersion("7.1.0");
        definitions("reviewTask,optionalNote", "reviewTask,approveTask");

        assertThatThrownBy(() -> service.migrateBatch(ENGINE, FROM, request(null), admin))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("plan-item-not-in-target");
                    assertThat(e.getMessage()).contains("optionalNote").contains("cohort door");
                });
        verify(cmmn, never()).migrateCase(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(audit);
    }

    /** #500 review: the batch pre-check is the only guard, so it must hold for either XML quote. */
    @Test
    void batchIsNotSentWhenASingleQuotedSourceModelHasAPlanItemTheTargetDrops() {
        engineVersion("7.1.0");
        definitions("reviewTask", "reviewTask");
        when(cmmn.cmmnDeploymentResourceData(engine, CallPriority.INTERACTIVE, "dep-1", "v1.cmmn"))
                .thenReturn("<case><planItem id='p1' definitionRef='reviewTask'/>"
                        + "<planItem id='p2' definitionRef='optionalNote'/></case>");

        assertThatThrownBy(() -> service.migrateBatch(ENGINE, FROM, request(null), admin))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("plan-item-not-in-target");
                    assertThat(e.getMessage()).contains("optionalNote");
                });
        verify(cmmn, never()).migrateCase(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(audit);
    }

    /** An empty source set proves nothing about the target: refuse, do not send an unchecked batch. */
    @Test
    void batchIsNotSentWhenNoPlanItemDefinitionCanBeReadFromTheSourceModel() {
        engineVersion("7.1.0");
        definitions("reviewTask", "reviewTask");
        when(cmmn.cmmnDeploymentResourceData(engine, CallPriority.INTERACTIVE, "dep-1", "v1.cmmn"))
                .thenReturn("<case><planItem id=p1 definitionRef=&quot;reviewTask&quot;/></case>");

        assertThatThrownBy(() -> service.migrateBatch(ENGINE, FROM, request(null), admin))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("source-model-unreadable");
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getMessage()).contains(FROM).contains("cohort door");
                });
        verify(cmmn, never()).migrateCase(any(), any(), any(), any(), any(), any());
        verify(cmmn, never()).countRunningCases(any(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void prodNeedsTheTypedCountOfTheFreshSetAndAnEmptySetIsRefused() {
        prod();
        engineVersion("7.1.0");
        definitions("reviewTask", "reviewTask");
        when(cmmn.countRunningCases(engine, CallPriority.INTERACTIVE, FROM)).thenReturn(4L, 4L, 0L);

        assertThatThrownBy(() -> service.migrateBatch(ENGINE, FROM, request(null), admin))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("confirm-count-required"));
        assertThatThrownBy(() -> service.migrateBatch(ENGINE, FROM, request(3), admin))
                .isInstanceOfSatisfying(BulkCountDriftException.class, e -> {
                    assertThat(e.confirmedCount()).isEqualTo(3);
                    assertThat(e.actualCount()).isEqualTo(4);
                });
        assertThatThrownBy(() -> service.migrateBatch(ENGINE, FROM, request(0), admin))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("nothing-to-migrate"));
        verify(cmmn, never()).migrateCase(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void theRailsRefuseANonAdminAReadOnlyEngineAShortReasonAndACrossKeyTarget() {
        engineVersion("7.1.0");
        when(rbac.hasRoleOn(any(), any(), eq(ENGINE))).thenReturn(false);
        assertThatThrownBy(() -> service.migrateHistoric(ENGINE, FROM, request(null), admin))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("rbac-denied"));
        when(rbac.hasRoleOn(any(), any(), eq(ENGINE))).thenReturn(true);

        assertThatThrownBy(() -> service.migrateHistoric(
                        ENGINE, FROM, new CaseDefinitionMigrationRequest(TO, "too short", null, null, null), admin))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("reason-too-short"));

        when(cmmn.getCmmnCaseDefinition(engine, CallPriority.INTERACTIVE, FROM))
                .thenReturn(Map.of("id", FROM, "key", "demoCase"));
        when(cmmn.getCmmnCaseDefinition(engine, CallPriority.INTERACTIVE, TO))
                .thenReturn(Map.of("id", TO, "key", "anotherCase"));
        assertThatThrownBy(() -> service.migrateHistoric(ENGINE, FROM, request(null), admin))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("cross-key-target"));

        engine = TestEngines.engine(
                ENGINE, "http://engine.test/flowable-rest/service", EngineEnvironment.DEV, EngineMode.READ_ONLY);
        assertThatThrownBy(() -> service.migrateHistoric(ENGINE, FROM, request(null), admin))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("engine-read-only"));
        verify(cmmn, never()).migrateCase(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void historicByDefinitionCountsEndedCasesAndReportsWhatIsLeft() {
        engineVersion("7.1.0");
        definitions("reviewTask,optionalNote", "reviewTask");
        when(cases.countEndedCases(engine, CallPriority.INTERACTIVE, FROM)).thenReturn(2L, 0L);

        ActionResult result = service.migrateHistoric(ENGINE, FROM, request(null), admin);

        verify(cmmn)
                .migrateCase(
                        eq(engine),
                        eq(CallPriority.INTERACTIVE),
                        eq("7.1.0"),
                        eq(CaseMigrationRoute.BY_DEFINITION_HISTORIC),
                        eq(FROM),
                        eq(Map.of("toCaseDefinitionId", TO)));
        verify(audit).close(pending, AuditOutcome.ok, 200, "{\"endedCasesLeftOnSource\":0}", true);
        assertThat(result.deltaStatement()).contains("2 ended cases").contains("0 ended case(s) are left");
    }

    @Test
    void aSingleHistoricCaseMustHaveEndedAndOnProdNeedsItsTypedId() {
        prod();
        engineVersion("7.1.0");
        when(cmmn.getHistoricCmmnCaseInstance(engine, CallPriority.INTERACTIVE, "running"))
                .thenReturn(Map.of("id", "running", "caseDefinitionId", FROM));
        assertThatThrownBy(() -> service.migrateHistoricCase(ENGINE, "running", request(null), admin))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("case-still-running"));
        when(cmmn.getHistoricCmmnCaseInstance(engine, CallPriority.INTERACTIVE, "missing"))
                .thenReturn(null);
        assertThatThrownBy(() -> service.migrateHistoricCase(ENGINE, "missing", request(null), admin))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("case-not-found"));

        definitions("reviewTask", "reviewTask");
        when(cmmn.getHistoricCmmnCaseInstance(engine, CallPriority.INTERACTIVE, "ended"))
                .thenReturn(Map.of("id", "ended", "caseDefinitionId", FROM, "endTime", "2026-11-12T10:00:00.000Z"));
        assertThatThrownBy(() -> service.migrateHistoricCase(ENGINE, "ended", request(1), admin))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("confirm-token-mismatch"));
        verify(cmmn, never()).migrateCase(any(), any(), any(), any(), any(), any());

        service.migrateHistoricCase(
                ENGINE, "ended", new CaseDefinitionMigrationRequest(TO, REASON, "INC-500", null, "ended"), admin);

        verify(cmmn)
                .migrateCase(
                        eq(engine),
                        eq(CallPriority.INTERACTIVE),
                        eq("7.1.0"),
                        eq(CaseMigrationRoute.HISTORIC),
                        eq("ended"),
                        eq(Map.of("toCaseDefinitionId", TO)));
        verify(audit)
                .beginPending(
                        any(),
                        eq(ENGINE),
                        any(),
                        eq("ended"),
                        eq(CaseDefinitionMigrationService.HISTORIC_CASE_ACTION),
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void anEngineRejectionIsAuditedFailedAndNeverRetried() {
        engineVersion("7.1.0");
        definitions("reviewTask", "reviewTask");
        when(cases.countEndedCases(engine, CallPriority.INTERACTIVE, FROM)).thenReturn(1L);
        org.mockito.Mockito.doThrow(HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Internal Server Error",
                        org.springframework.http.HttpHeaders.EMPTY,
                        "{\"message\":\"engine said no\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8))
                .when(cmmn)
                .migrateCase(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.migrateHistoric(ENGINE, FROM, request(null), admin))
                .isInstanceOf(EngineRejectedException.class);

        verify(audit).close(eq(pending), eq(AuditOutcome.failed), eq(500), any(), eq(false));
        verify(cmmn, org.mockito.Mockito.times(1)).migrateCase(any(), any(), any(), any(), any(), any());
    }

    @Test
    void theOnRampCountsRunningAndEndedCasesAndNamesTheGateOnASixEightEngine() {
        engineVersion("6.8.0");
        when(cases.listCaseDefinitionVersionsDesc(engine, CallPriority.INTERACTIVE, "demoCase", 50))
                .thenReturn(new FlowablePage(
                        List.of(
                                Map.of("id", TO, "version", 2, "name", "Demo", "deploymentId", "dep-2"),
                                Map.of("id", FROM, "version", 1, "name", "Demo", "deploymentId", "dep-1")),
                        2,
                        0,
                        50));
        when(cmmn.countRunningCases(engine, CallPriority.INTERACTIVE, FROM)).thenReturn(7L);
        when(cases.countEndedCases(engine, CallPriority.INTERACTIVE, FROM)).thenReturn(2L);

        CaseDefinitionVersionsResponse response = service.versions(ENGINE, "demoCase");

        assertThat(response.latestVersion()).isEqualTo(2);
        assertThat(response.complete()).isTrue();
        assertThat(response.versions())
                .extracting(CaseDefinitionVersionsResponse.CaseDefinitionVersion::version)
                .containsExactly(2, 1);
        assertThat(response.versions().get(1).runningCaseCount()).isEqualTo(7);
        assertThat(response.versions().get(1).endedCaseCount()).isEqualTo(2);
        assertThat(response.definitionRoutesGate())
                .contains("needs Flowable 7.x")
                .contains("6.8.0");
        // No batch route below 7.x: the batch read answers empty without asking the engine.
        assertThat(service.batches(ENGINE, FROM)).isEmpty();
        verify(cases, never()).listCaseMigrationBatches(any(), any(), any(), anyInt());
    }

    @Test
    void batchesTallyPartsByTheEnginesOwnStatus() {
        engineVersion("7.1.0");
        when(cases.listCaseMigrationBatches(eq(engine), eq(CallPriority.INTERACTIVE), eq(FROM), anyInt()))
                .thenReturn(new FlowablePage(
                        List.of(Map.of(
                                "id",
                                "b-1",
                                "status",
                                "completed",
                                "searchKey",
                                FROM,
                                "searchKey2",
                                TO,
                                "createTime",
                                "2026-11-12T11:00:00.000Z")),
                        1,
                        0,
                        20));
        when(cases.listBatchParts(engine, CallPriority.INTERACTIVE, "b-1"))
                .thenReturn(List.of(
                        Map.of("scopeId", "case-1", "status", "success"),
                        Map.of("scopeId", "case-2", "status", "fail"),
                        Map.of("scopeId", "case-3", "status", "success")));

        List<CaseMigrationBatchView> batches = service.batches(ENGINE, FROM);

        assertThat(batches).hasSize(1);
        assertThat(batches.get(0).parts()).isEqualTo(3);
        assertThat(batches.get(0).partsByStatus()).containsEntry("success", 2).containsEntry("fail", 1);
        assertThat(batches.get(0).notSucceededCaseIds()).containsExactly("case-2");
        assertThat(batches.get(0).toCaseDefinitionId()).isEqualTo(TO);
    }
}
