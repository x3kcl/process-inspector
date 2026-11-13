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
import io.inspector.migration.CaseCohortBulkMigration;
import io.inspector.migration.CaseCohortBulkMigration.Cohort;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import io.inspector.support.TestEngines;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

/** Rung 1: the case cohort bulk doors (#500) — scope resolution and rails, never dispatch. */
@ExtendWith(MockitoExtension.class)
class BulkCaseMigrationServiceTest {

    @Mock
    BulkJobService bulk;

    @Mock
    BulkJobRepository jobs;

    @Mock
    BulkJobItemRepository items;

    @Mock
    CaseCohortBulkMigration cohorts;

    @Mock
    RbacAuthorizer rbac;

    @Mock
    TicketPolicy tickets;

    @Mock
    Authentication auth;

    private final UUID devAnalysis = UUID.randomUUID();
    private final UUID prodAnalysis = UUID.randomUUID();
    private BulkCaseMigrationService service;

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
        service = new BulkCaseMigrationService(bulk, jobs, items, cohorts, registry, rbac, tickets);
        lenient().when(rbac.hasRoleOn(any(), eq(Role.ADMIN), anyString())).thenReturn(true);
        lenient()
                .when(cohorts.resolve(devAnalysis))
                .thenReturn(new Cohort(devAnalysis, "engine-a", "def-1a", "def-2a", List.of("a1", "a2"), 1, Map.of()));
        lenient()
                .when(cohorts.resolve(prodAnalysis))
                .thenReturn(new Cohort(prodAnalysis, "engine-p", "def-1p", "def-2p", List.of("p1"), 0, Map.of()));
    }

    private BulkDtos.BulkMigrateRequest request(Integer confirmedCount, UUID... ids) {
        return new BulkDtos.BulkMigrateRequest(List.of(ids), "move off the bad deploy", null, confirmedCount);
    }

    @Test
    void crossEngineAnalysesBecomeOneJobOfTheirExecutableSets() {
        service.submit(request(3, devAnalysis, prodAnalysis), auth);

        ArgumentCaptor<BulkDtos.BulkSubmitRequest> sent = ArgumentCaptor.forClass(BulkDtos.BulkSubmitRequest.class);
        verify(bulk).submitCaseMigration(sent.capture(), eq(auth), anyMap(), anyString());
        assertThat(sent.getValue().verb()).isEqualTo("migrate-case");
        assertThat(sent.getValue().items())
                .containsExactly(
                        new BulkDtos.BulkTarget("engine-a", "a1", devAnalysis.toString()),
                        new BulkDtos.BulkTarget("engine-a", "a2", devAnalysis.toString()),
                        new BulkDtos.BulkTarget("engine-p", "p1", prodAnalysis.toString()));
    }

    @Test
    void prodNeedsTheTypedCountOfTheExecutableSet() {
        assertThatThrownBy(() -> service.submit(request(null, devAnalysis, prodAnalysis), auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo("bulk-migrate-confirm-count-required"));
        assertThatThrownBy(() -> service.submit(request(2, devAnalysis, prodAnalysis), auth))
                .isInstanceOf(BulkCountDriftException.class);
        verify(bulk, never()).submitCaseMigration(any(), any(), any(), any());
    }

    @Test
    void devOnlyNeedsNoTypedCount() {
        service.submit(request(null, devAnalysis), auth);
        verify(bulk).submitCaseMigration(any(), eq(auth), anyMap(), anyString());
    }

    @Test
    void refusesWithoutAdminOnTheAnalysisEngine() {
        when(rbac.hasRoleOn(auth, Role.ADMIN, "engine-p")).thenReturn(false);
        assertThatThrownBy(() -> service.submit(request(3, devAnalysis, prodAnalysis), auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("rbac-denied"));
        verify(bulk, never()).submitCaseMigration(any(), any(), any(), any());
    }

    @Test
    void refusesAnAllBlockerCohort() {
        UUID blocked = UUID.randomUUID();
        when(cohorts.resolve(blocked))
                .thenReturn(new Cohort(blocked, "engine-a", "def-1x", "def-2x", List.of(), 4, Map.of()));
        assertThatThrownBy(() -> service.submit(request(null, blocked), auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo("analysis-nothing-executable"));
    }

    @Test
    void continuationTakesOnlyThePriorJobsNotRunAndFailedItems() {
        UUID prior = UUID.randomUUID();
        BulkJob job = new BulkJob(prior, "admin", Instant.EPOCH, "migrate-case", "reason", "OPS-9", 3, null);
        when(jobs.findById(prior)).thenReturn(Optional.of(job));
        BulkJobItem ok = new BulkJobItem(prior, 0, "engine-a", "a1", devAnalysis.toString(), BulkJobItem.State.ok);
        BulkJobItem notRun =
                new BulkJobItem(prior, 1, "engine-a", "a2", devAnalysis.toString(), BulkJobItem.State.not_run);
        when(items.findByJobIdOrderByOrdinal(prior)).thenReturn(List.of(ok, notRun));

        BulkDtos.BulkTarget a2 = new BulkDtos.BulkTarget("engine-a", "a2", devAnalysis.toString());
        service.continueJob(
                new BulkDtos.BulkSubmitRequest("migrate-case", "continue the move", null, prior, List.of(a2)), auth);
        ArgumentCaptor<BulkDtos.BulkSubmitRequest> sent = ArgumentCaptor.forClass(BulkDtos.BulkSubmitRequest.class);
        verify(bulk).submitCaseMigration(sent.capture(), eq(auth), anyMap(), anyString());
        assertThat(sent.getValue().continuedFrom()).isEqualTo(prior);
        assertThat(sent.getValue().ticketId()).isEqualTo("OPS-9");

        BulkDtos.BulkTarget a1 = new BulkDtos.BulkTarget("engine-a", "a1", devAnalysis.toString());
        assertThatThrownBy(() -> service.continueJob(
                        new BulkDtos.BulkSubmitRequest("migrate-case", "re-fire an ok item", null, prior, List.of(a1)),
                        auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo("continuation-scope-mismatch"));
    }

    @Test
    void continuationRefusesAJobOfAnotherVerbAndAnOverlappingAnalysisIsRefused() {
        UUID prior = UUID.randomUUID();
        when(jobs.findById(prior))
                .thenReturn(Optional.of(
                        new BulkJob(prior, "admin", Instant.EPOCH, "migrate-instance", "reason", null, 1, null)));
        assertThatThrownBy(() -> service.continueJob(
                        new BulkDtos.BulkSubmitRequest("migrate-case", "continue the move", null, prior, List.of()),
                        auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("bulk-job-unknown"));

        UUID twin = UUID.randomUUID();
        when(cohorts.resolve(twin))
                .thenReturn(new Cohort(twin, "engine-a", "def-1a", "def-2a", List.of("a2"), 0, Map.of()));
        assertThatThrownBy(() -> service.submit(request(null, devAnalysis, twin), auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("analysis-overlap"));
        assertThatThrownBy(() -> service.submit(request(null), auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("analysis-required"));
        assertThat(BulkCaseMigrationService.isMigrateCase("migrate-case")).isTrue();
        assertThat(BulkCaseMigrationService.isMigrateCase("migrate-instance")).isFalse();
    }

    @Test
    void migrateCaseWithoutAPriorJobIsRefusedOnTheSelectionDoor() {
        assertThatThrownBy(() -> service.continueJob(
                        new BulkDtos.BulkSubmitRequest(
                                "migrate-case",
                                "crafted target list",
                                null,
                                null,
                                List.of(new BulkDtos.BulkTarget("engine-a", "x", devAnalysis.toString()))),
                        auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("migrate-needs-analysis"));
    }
}
