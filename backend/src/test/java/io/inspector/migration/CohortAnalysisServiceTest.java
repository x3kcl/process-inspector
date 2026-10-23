package io.inspector.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.action.GuardRefusedException;
import io.inspector.migration.CohortFindingAggregator.InstanceRow;
import io.inspector.migration.CohortMigrationService.ListedCohort;
import io.inspector.migration.MigrationFinding.Code;
import io.inspector.migration.MigrationFinding.Severity;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class CohortAnalysisServiceTest {

    @Mock
    CohortMigrationService cohorts;

    @Mock
    MigrationService migration;

    @Mock
    MigrationAnalysisRepository analyses;

    @Mock
    RbacAuthorizer rbac;

    @Mock
    ApplicationEventPublisher events;

    @Mock
    Authentication auth;

    private final Map<UUID, MigrationAnalysis> rows = new ConcurrentHashMap<>();
    private CohortAnalysisService service;

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
        service = new CohortAnalysisService(
                cohorts, migration, analyses, rbac, new ObjectMapper(), Clock.systemUTC(), events, Runnable::run);
    }

    @Test
    void persistsTheAggregateAndRereadsTheStoredRow() {
        when(rbac.hasRoleOn(auth, Role.ADMIN, "engine-a")).thenReturn(true);
        when(cohorts.listInstances(eq("engine-a"), any()))
                .thenReturn(new ListedCohort("demo:1:x", null, List.of("b1", "w1", "c1"), 3, false));
        when(migration.preview(eq("engine-a"), eq("b1"), any(), eq(auth)))
                .thenReturn(preview(
                        "b1",
                        List.of(activity(
                                "reviewTask",
                                finding(
                                        Code.UNMAPPED_ACTIVE_ACTIVITY,
                                        Severity.BLOCKER_ADVICE,
                                        "reviewTask",
                                        "nothing to send"))),
                        List.of()));
        when(migration.preview(eq("engine-a"), eq("w1"), any(), eq(auth)))
                .thenReturn(preview(
                        "w1",
                        List.of(activity(
                                "stepA", finding(Code.NESTING_PATH_CHANGED, Severity.WARNING, "stepA", "moved"))),
                        List.of()));
        when(migration.preview(eq("engine-a"), eq("c1"), any(), eq(auth)))
                .thenReturn(preview(
                        "c1",
                        List.of(),
                        List.of(finding(Code.BOUNDARY_CLOCK_RESET, Severity.INFO, null, "clock may reset"))));

        CohortPreviewRequest filter = new CohortPreviewRequest(
                "demo:1:x", null, null, null, null, null, null, null, List.of(MigrationMapping.oneToOne("a", "b")));
        CohortAnalysisView started = service.start("engine-a", filter, auth);

        assertThat(started.status()).isEqualTo("COMPLETED");
        assertThat(started.taxonomyVersion()).isEqualTo(MigrationFinding.TAXONOMY_VERSION);
        assertThat(started.sourceDefinitionId()).isEqualTo("demo:1:x");
        assertThat(started.targetDefinitionId()).isEqualTo("demo:2:y");
        assertThat(started.filter().fromDefinitionId()).isEqualTo("demo:1:x");
        assertThat(started.filter().mappings()).hasSize(1);
        assertThat(started.blockerCount()).isEqualTo(1);
        assertThat(started.warningCount()).isEqualTo(1);
        assertThat(started.cleanCount()).isEqualTo(1);
        assertThat(started.executableIds()).containsExactly("w1", "c1");
        assertThat(started.blockers())
                .extracting(CohortFindingAggregator.BlockedInstance::instanceId)
                .containsExactly("b1");
        assertThat(started.codeCounts())
                .containsEntry("UNMAPPED_ACTIVE_ACTIVITY", 1)
                .containsEntry("BOUNDARY_CLOCK_RESET", 1);
        assertThat(started.sampleInstanceIds()).containsExactly("b1", "w1", "c1");
        assertThat(started.banner()).isEqualTo(CohortAnalysisView.BANNER);
        assertThat(started.banner()).isEqualTo("Inspector estimate — the engine is the only ground truth at execute");
        assertThat(started.lowerBound()).isFalse();

        CohortAnalysisView again = service.get(started.id(), 1, 1, auth);
        assertThat(again.executableIds()).containsExactly("w1", "c1");
        assertThat(again.instances()).extracting(InstanceRow::instanceId).containsExactly("w1");
        assertThat(again.instanceTotal()).isEqualTo(3);
        assertThat(again.codeCounts()).isEqualTo(started.codeCounts());
        verify(migration, times(3)).preview(eq("engine-a"), any(), any(), eq(auth));
        verify(migration, never()).execute(any(), any(), any(), any());
        verify(migration, never()).executeFromCohort(any(), any(), any(), any());
        verify(events, atLeastOnce()).publishEvent(any(MigrationAnalysisChangedEvent.class));
    }

    @Test
    void stopsWhenTheEngineIsUnreachableAndDoesNotCountTheRestClean() {
        when(rbac.hasRoleOn(auth, Role.ADMIN, "engine-a")).thenReturn(true);
        when(cohorts.listInstances(eq("engine-a"), any()))
                .thenReturn(new ListedCohort("demo:1:x", "demo:2:y", List.of("i1", "i2"), 2, false));
        when(migration.preview(eq("engine-a"), eq("i1"), any(), eq(auth)))
                .thenThrow(new GuardRefusedException(HttpStatus.BAD_GATEWAY, "engine-unreachable", "engine down"));

        CohortAnalysisView view = service.start(
                "engine-a",
                new CohortPreviewRequest("demo:1:x", "demo:2:y", null, null, null, null, null, null, null),
                auth);

        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.lowerBound()).isTrue();
        assertThat(view.boundLabel()).contains("lower bound");
        assertThat(view.blockerCount()).isEqualTo(1);
        assertThat(view.warningCount()).isZero();
        assertThat(view.cleanCount()).isZero();
        assertThat(view.executableIds()).isEmpty();
        assertThat(view.failure()).isEqualTo("engine down");
        verify(migration, never()).preview(eq("engine-a"), eq("i2"), any(), any());
        verify(migration, never()).executeFromCohort(any(), any(), any(), any());
    }

    @Test
    void refusesANonAdminBeforeListingTheCohort() {
        when(rbac.hasRoleOn(auth, Role.ADMIN, "engine-a")).thenReturn(false);
        assertThatThrownBy(() -> service.start(
                        "engine-a",
                        new CohortPreviewRequest("demo:1:x", null, null, null, null, null, null, null, null),
                        auth))
                .isInstanceOf(GuardRefusedException.class);
        verify(cohorts, never()).listInstances(any(), any());
        verify(migration, never()).preview(any(), any(), any(), any());
    }

    private static MigrationPreview preview(
            String instanceId, List<ActivityDiffEntry> activities, List<MigrationFinding> findings) {
        return new MigrationPreview(
                "engine-a",
                instanceId,
                "demo:1:x",
                "demo",
                1,
                "demo:2:y",
                2,
                false,
                findings.stream().noneMatch(MigrationFinding::isBlockerAdvice)
                        && activities.stream().noneMatch(ActivityDiffEntry::isBlocker),
                activities,
                findings,
                List.of(),
                "digest",
                0,
                "POST",
                "/runtime/process-instances/" + instanceId + "/migrate",
                Map.of(),
                "summary",
                MigrationPreview.BANNER);
    }

    private static ActivityDiffEntry activity(String activityId, MigrationFinding finding) {
        return new ActivityDiffEntry(
                activityId,
                "userTask",
                activityId,
                ActivityDiffEntry.Status.FLAGGED_UNMAPPED,
                null,
                null,
                finding.detail(),
                List.of(finding));
    }

    private static MigrationFinding finding(Code code, Severity severity, String activityId, String detail) {
        return new MigrationFinding(code, severity, activityId, detail);
    }
}
