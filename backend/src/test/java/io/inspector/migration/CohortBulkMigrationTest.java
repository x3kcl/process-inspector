package io.inspector.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.action.ActionResult;
import io.inspector.action.GuardRefusedException;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
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

/** Rung 1: the analysis → bulk-item bridge (#496). The engine call itself is MigrationService's. */
@ExtendWith(MockitoExtension.class)
class CohortBulkMigrationTest {

    @Mock
    MigrationAnalysisRepository analyses;

    @Mock
    MigrationService migration;

    @Mock
    ProcessApiClient client;

    @Mock
    EngineRegistry registry;

    @Mock
    Authentication auth;

    @Mock
    RbacAuthorizer rbac;

    private final ObjectMapper json = new ObjectMapper();
    private final UUID id = UUID.randomUUID();
    private CohortBulkMigration bridge;

    @BeforeEach
    void setup() {
        bridge = new CohortBulkMigration(analyses, migration, client, registry, json, rbac);
    }

    private void stored(MigrationAnalysis.Status status, boolean lowerBound, Map<String, String> digests)
            throws Exception {
        CohortPreviewRequest filter = new CohortPreviewRequest(
                "demo:1:x",
                "demo:2:y",
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(MigrationMapping.oneToOne("reviewTask", "approveTask")));
        CohortAnalysisService.Payload payload = new CohortAnalysisService.Payload(
                lowerBound,
                lowerBound ? "lower bound — estimated 2 of 900" : null,
                3,
                1,
                0,
                2,
                List.of(),
                List.of(new CohortFindingAggregator.BlockedInstance("b1", "unmapped")),
                List.of("c1", "c2"),
                List.of(),
                null,
                digests);
        MigrationAnalysis row = new MigrationAnalysis(
                id,
                "engine-a",
                json.writeValueAsString(filter),
                "demo:1:x",
                "demo:2:y",
                MigrationFinding.TAXONOMY_VERSION,
                "{}",
                "[]",
                json.writeValueAsString(payload),
                Instant.EPOCH);
        if (status != MigrationAnalysis.Status.RUNNING) {
            row.update(status, "demo:2:y", "{}", "[]", json.writeValueAsString(payload), Instant.EPOCH);
        }
        when(analyses.findById(id)).thenReturn(Optional.of(row));
    }

    @Test
    void resolvesTheExecutableSetWithoutTheBlocker() throws Exception {
        stored(MigrationAnalysis.Status.COMPLETED, false, Map.of("b1", "d0", "c1", "d1", "c2", "d2"));

        CohortBulkMigration.Cohort cohort = bridge.resolve(id);

        assertThat(cohort.executableIds()).containsExactly("c1", "c2");
        assertThat(cohort.blockerCount()).isEqualTo(1);
        assertThat(cohort.toDefinitionId()).isEqualTo("demo:2:y");
        assertThat(cohort.mappings()).hasSize(1);
    }

    @Test
    void refusesARunningAPartialAndADigestlessAnalysis() throws Exception {
        stored(MigrationAnalysis.Status.RUNNING, false, Map.of("c1", "d1", "c2", "d2"));
        assertThatThrownBy(() -> bridge.resolve(id))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("analysis-not-completed"));

        stored(MigrationAnalysis.Status.COMPLETED, true, Map.of("c1", "d1", "c2", "d2"));
        assertThatThrownBy(() -> bridge.resolve(id))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("analysis-partial"));

        stored(MigrationAnalysis.Status.COMPLETED, false, null);
        assertThatThrownBy(() -> bridge.resolve(id))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("analysis-stale"));
    }

    @Test
    void dispatchAssertsTheAnalysisDigestAndSourceAsTheCompareAndSet() throws Exception {
        stored(MigrationAnalysis.Status.COMPLETED, false, Map.of("c1", "d1", "c2", "d2"));
        when(migration.executeBulkItem(eq("engine-a"), eq("c2"), any(), eq(auth)))
                .thenReturn(new ActionResult(UUID.randomUUID(), "c", "ok", 200, "migrated"));

        bridge.dispatch(id.toString(), "engine-a", "c2", "move off the bad deploy", "OPS-1", auth);

        ArgumentCaptor<MigrationRequest> sent = ArgumentCaptor.forClass(MigrationRequest.class);
        verify(migration).executeBulkItem(eq("engine-a"), eq("c2"), sent.capture(), eq(auth));
        MigrationRequest request = sent.getValue();
        assertThat(request.expectedActivityStateDigest()).isEqualTo("d2");
        assertThat(request.expectedFromDefinitionId()).isEqualTo("demo:1:x");
        assertThat(request.toDefinitionId()).isEqualTo("demo:2:y");
        assertThat(request.mappingsOrEmpty()).hasSize(1);
        assertThat(request.reason()).isEqualTo("move off the bad deploy");
        assertThat(request.ticketId()).isEqualTo("OPS-1");
        assertThat(request.confirmToken()).isNull();
    }

    @Test
    void neverSendsAnInstanceOutsideTheAnalysisOrOnAnotherEngine() throws Exception {
        stored(MigrationAnalysis.Status.COMPLETED, false, Map.of("c1", "d1", "c2", "d2"));

        assertThatThrownBy(() -> bridge.dispatch(id.toString(), "engine-a", "b1", "reason here", null, auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("not-in-analysis"));
        assertThatThrownBy(() -> bridge.dispatch(id.toString(), "engine-b", "c1", "reason here", null, auth))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("not-in-analysis"));
        verify(migration, never()).executeBulkItem(any(), any(), any(), any());
    }

    @Test
    void verifyReadsTheDefinitionIdOnly() throws Exception {
        stored(MigrationAnalysis.Status.COMPLETED, false, Map.of("c1", "d1", "c2", "d2"));
        EngineConfig engine = TestEngines.engine("engine-a", "http://a", EngineEnvironment.DEV, EngineMode.READ_WRITE);
        when(registry.require("engine-a")).thenReturn(engine);
        when(client.getRuntimeProcessInstance(eq(engine), any(), eq("c1")))
                .thenReturn(Map.of("processDefinitionId", "demo:2:y"));
        when(client.getRuntimeProcessInstance(eq(engine), any(), eq("c2")))
                .thenReturn(Map.of("processDefinitionId", "demo:1:x"));

        assertThat(bridge.verify(id.toString(), "engine-a", "c1").migrated()).isTrue();
        CohortBulkMigration.Verification stillOld = bridge.verify(id.toString(), "engine-a", "c2");
        assertThat(stillOld.migrated()).isFalse();
        assertThat(stillOld.evidence()).contains("demo:1:x");
        verify(migration, never()).executeBulkItem(any(), any(), any(), any());
    }
}
