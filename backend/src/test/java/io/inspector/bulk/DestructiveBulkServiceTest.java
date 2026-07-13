package io.inspector.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.inspector.action.GuardRefusedException;
import io.inspector.aggregate.SearchService;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.dto.ProcessInstanceRow;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.dto.SearchResponse;
import io.inspector.dto.SearchResponse.EngineResult;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import io.inspector.support.TestEngines;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * Rung 1 for the tier-4 destructive-bulk wizard (issue #100): the load-bearing properties are
 * refuse-unscoped, the ADMIN hard-gate firing BEFORE any resolution work, and the typed-count
 * drift check running against a FRESH resolution — never the preview's snapshot. The resolver
 * (SearchService) and BulkJobService are proven seams (mocked); the shared resolution honesty
 * checks (degraded/truncated/drained/cap) are BulkFilterResolution's own — proven once via
 * BulkFilterServiceTest, not re-litigated exhaustively here.
 */
class DestructiveBulkServiceTest {

    private static final String DEV = "dev-engine";
    private static final String PROD = "prod-engine";

    private final SearchService search = mock(SearchService.class);
    private final BulkJobService bulk = mock(BulkJobService.class);
    private final RbacAuthorizer rbac = mock(RbacAuthorizer.class);
    private final EngineRegistry registry = new EngineRegistry(new InspectorProperties(
            null,
            null,
            null,
            null,
            List.of(
                    TestEngines.engine(DEV, "http://localhost:1", EngineEnvironment.DEV, EngineMode.READ_WRITE),
                    TestEngines.engine(PROD, "http://localhost:1", EngineEnvironment.PROD, EngineMode.READ_WRITE))));
    private final Authentication admin = new TestingAuthenticationToken("adm", "n/a", "ROLE_ADMIN");

    private final DestructiveBulkService service = new DestructiveBulkService(search, bulk, registry, rbac);

    /* ------------------------- pre-flight refusals (no resolution) ------------------------- */

    @Test
    void onlyTerminateDeleteIsAllowedInThisRelease() {
        assertThatThrownBy(
                        () -> service.submit(request("delete-deadletter", narrowedCriteria(DEV), "teardown ok"), admin))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("bulk-verb-not-allowed"));
        verifyNoInteractions(search, bulk, rbac);
    }

    @Test
    void missingCriteriaRefused() {
        assertThatThrownBy(() -> service.submit(
                        new BulkDtos.BulkDestructiveRequest(null, "terminate-delete", "teardown ok", null, null),
                        admin))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("filter-criteria-required"));
        verifyNoInteractions(search, bulk, rbac);
    }

    @Test
    void openEndedStatusSetRefused() {
        SearchRequest noStatuses = criteria(DEV, null, "payment");
        assertThatThrownBy(() -> service.submit(request("terminate-delete", noStatuses, "teardown ok"), admin))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("filter-statuses-required"));
        verifyNoInteractions(search, bulk, rbac);
    }

    @Test
    void completedChipRefusedAsNotActionable() {
        SearchRequest withCompleted =
                criteria(DEV, List.of(InstanceStatus.ACTIVE, InstanceStatus.COMPLETED), "payment");
        assertThatThrownBy(() -> service.submit(request("terminate-delete", withCompleted, "teardown ok"), admin))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo("filter-completed-not-actionable"));
        verifyNoInteractions(search, bulk, rbac);
    }

    @Test
    void unscopedSweepRefusedOutright() {
        // Status + engine alone is NOT a narrowing filter (SPEC §6 tier 4: "refuse-unscoped").
        SearchRequest unscoped = criteria(DEV, List.of(InstanceStatus.ACTIVE), null);
        assertThatThrownBy(() -> service.submit(request("terminate-delete", unscoped, "teardown ok"), admin))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("bulk-destructive-unscoped"));
        verifyNoInteractions(search, bulk, rbac);
    }

    @Test
    void aDefinitionKeyAloneCountsAsNarrowing() {
        when(rbac.hasRoleOn(any(), eq(Role.ADMIN), eq(DEV))).thenReturn(true);
        when(search.resolveAllMatching(any(), anyInt()))
                .thenReturn(response(List.of(row(DEV, "p-1")), Map.of(DEV, EngineResult.success(1, 1))));
        when(bulk.submitDestructive(any(), eq(admin), anyMap(), any())).thenReturn(mock(BulkDtos.BulkJobDto.class));

        service.submit(request("terminate-delete", narrowedCriteria(DEV), "teardown ok"), admin);

        verify(bulk).submitDestructive(any(), eq(admin), anyMap(), any());
    }

    @Test
    void shortReasonRefusedAfterScopeCheckButBeforeRbac() {
        assertThatThrownBy(() -> service.submit(request("terminate-delete", narrowedCriteria(DEV), "short"), admin))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("reason-too-short"));
        verifyNoInteractions(search, bulk, rbac);
    }

    @Test
    void nonAdminOnTheTargetEngineRefusedBeforeAnyResolution() {
        when(rbac.hasRoleOn(any(), eq(Role.ADMIN), eq(DEV))).thenReturn(false);

        assertThatThrownBy(
                        () -> service.submit(request("terminate-delete", narrowedCriteria(DEV), "teardown ok"), admin))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("bulk-destructive-rbac-denied");
                    assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
                });
        verifyNoInteractions(search, bulk);
    }

    @Test
    void unnamedEngineScopeChecksAdminAcrossTheWholeFleet() {
        // No engineIds named — the RBAC fail-fast checks EVERY enabled engine, so an
        // ADMIN-on-dev-only operator cannot dodge the floor by omitting the prod engine.
        when(rbac.hasRoleOn(any(), eq(Role.ADMIN), eq(DEV))).thenReturn(true);
        when(rbac.hasRoleOn(any(), eq(Role.ADMIN), eq(PROD))).thenReturn(false);
        SearchRequest fleetWide = new SearchRequest(
                null,
                List.of(InstanceStatus.ACTIVE),
                "payment",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        assertThatThrownBy(() -> service.submit(request("terminate-delete", fleetWide, "teardown ok"), admin))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo("bulk-destructive-rbac-denied"));
        verifyNoInteractions(search, bulk);
    }

    /* ------------------------- dev-only scope: no typed count needed ------------------------- */

    @Test
    void devOnlyScopeNeedsNoConfirmedCount() {
        when(rbac.hasRoleOn(any(), eq(Role.ADMIN), eq(DEV))).thenReturn(true);
        when(search.resolveAllMatching(any(), anyInt()))
                .thenReturn(
                        response(List.of(row(DEV, "p-1"), row(DEV, "p-2")), Map.of(DEV, EngineResult.success(2, 2))));
        BulkDtos.BulkJobDto dto = mock(BulkDtos.BulkJobDto.class);
        when(bulk.submitDestructive(any(), eq(admin), anyMap(), any())).thenReturn(dto);

        BulkDtos.BulkJobDto out =
                service.submit(request("terminate-delete", narrowedCriteria(DEV), "teardown ok"), admin);

        assertThat(out).isSameAs(dto);
        ArgumentCaptor<BulkDtos.BulkSubmitRequest> submit = ArgumentCaptor.forClass(BulkDtos.BulkSubmitRequest.class);
        verify(bulk).submitDestructive(submit.capture(), eq(admin), anyMap(), any());
        assertThat(submit.getValue().verb()).isEqualTo("terminate-delete");
        assertThat(submit.getValue().items()).hasSize(2);
    }

    /* ------------------------- PROD scope: typed-count attestation ------------------------- */

    @Test
    void prodScopeWithoutConfirmedCountRefused() {
        when(rbac.hasRoleOn(any(), eq(Role.ADMIN), eq(PROD))).thenReturn(true);
        when(search.resolveAllMatching(any(), anyInt()))
                .thenReturn(response(List.of(row(PROD, "p-1")), Map.of(PROD, EngineResult.success(1, 1))));

        assertThatThrownBy(() -> service.submit(
                        new BulkDtos.BulkDestructiveRequest(
                                narrowedCriteria(PROD), "terminate-delete", "teardown ok", null, null),
                        admin))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo("bulk-destructive-confirm-count-required"));
        verifyNoInteractions(bulk);
    }

    @Test
    void prodScopeWithAWrongConfirmedCountDriftsRatherThanActs() {
        when(rbac.hasRoleOn(any(), eq(Role.ADMIN), eq(PROD))).thenReturn(true);
        when(search.resolveAllMatching(any(), anyInt()))
                .thenReturn(response(
                        List.of(row(PROD, "p-1"), row(PROD, "p-2")), Map.of(PROD, EngineResult.success(2, 2))));

        assertThatThrownBy(() -> service.submit(
                        new BulkDtos.BulkDestructiveRequest(
                                narrowedCriteria(PROD), "terminate-delete", "teardown ok", null, 5),
                        admin))
                .isInstanceOfSatisfying(BulkCountDriftException.class, e -> {
                    assertThat(e.confirmedCount()).isEqualTo(5);
                    assertThat(e.actualCount()).isEqualTo(2);
                });
        verifyNoInteractions(bulk);
    }

    @Test
    void prodScopeWithTheRightConfirmedCountDispatches() {
        when(rbac.hasRoleOn(any(), eq(Role.ADMIN), eq(PROD))).thenReturn(true);
        when(search.resolveAllMatching(any(), anyInt()))
                .thenReturn(response(List.of(row(PROD, "p-1")), Map.of(PROD, EngineResult.success(1, 1))));
        BulkDtos.BulkJobDto dto = mock(BulkDtos.BulkJobDto.class);
        when(bulk.submitDestructive(any(), eq(admin), anyMap(), any())).thenReturn(dto);

        BulkDtos.BulkJobDto out = service.submit(
                new BulkDtos.BulkDestructiveRequest(narrowedCriteria(PROD), "terminate-delete", "teardown ok", null, 1),
                admin);

        assertThat(out).isSameAs(dto);
        verify(bulk).submitDestructive(any(), eq(admin), anyMap(), any());
    }

    /* ------------------------- resolution honesty (delegated) ------------------------- */

    @Test
    void drainedScopeRefusedAsConflict() {
        when(rbac.hasRoleOn(any(), eq(Role.ADMIN), eq(DEV))).thenReturn(true);
        when(search.resolveAllMatching(any(), anyInt()))
                .thenReturn(response(List.of(), Map.of(DEV, EngineResult.success(0, 0))));

        assertThatThrownBy(
                        () -> service.submit(request("terminate-delete", narrowedCriteria(DEV), "teardown ok"), admin))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("filter-drained");
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                });
        verifyNoInteractions(bulk);
    }

    /* ------------------------- preview ------------------------- */

    @Test
    void previewNeverTouchesBulkAndReportsPerEngineCounts() {
        when(rbac.hasRoleOn(any(), eq(Role.ADMIN), eq(DEV))).thenReturn(true);
        when(search.resolveAllMatching(any(), anyInt()))
                .thenReturn(
                        response(List.of(row(DEV, "p-1"), row(DEV, "p-2")), Map.of(DEV, EngineResult.success(2, 2))));

        BulkDtos.BulkDestructivePreview preview =
                service.preview(request("terminate-delete", narrowedCriteria(DEV), "teardown ok"), admin);

        assertThat(preview.count()).isEqualTo(2);
        assertThat(preview.perEngineCounts()).containsEntry(DEV, 2L);
        assertThat(preview.sampleRows()).hasSize(2);
        assertThat(preview.capped()).isFalse();
        assertThat(preview.prodInScope()).isFalse();
        verifyNoInteractions(bulk);
    }

    /* ------------------------- fixtures ------------------------- */

    private static BulkDtos.BulkDestructiveRequest request(String verb, SearchRequest criteria, String reason) {
        return new BulkDtos.BulkDestructiveRequest(criteria, verb, reason, null, null);
    }

    /** A definition key is the narrowing dimension — satisfies refuse-unscoped. */
    private static SearchRequest narrowedCriteria(String engineId) {
        return criteria(engineId, List.of(InstanceStatus.ACTIVE), "payment");
    }

    private static SearchRequest criteria(String engineId, List<InstanceStatus> statuses, String definitionKey) {
        return new SearchRequest(
                List.of(engineId),
                statuses,
                definitionKey,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static SearchResponse response(List<ProcessInstanceRow> rows, Map<String, EngineResult> perEngine) {
        return new SearchResponse(rows, perEngine, Map.of(), null, null);
    }

    private static ProcessInstanceRow row(String engineId, String instanceId) {
        return new ProcessInstanceRow(
                engineId + ":" + instanceId,
                engineId,
                null,
                null,
                instanceId,
                null,
                null,
                "payment",
                null,
                3,
                null,
                "bpmn",
                InstanceStatus.ACTIVE,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
