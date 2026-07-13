package io.inspector.aggregate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.InstanceStatusFlags;
import io.inspector.dto.ProcessInstanceRow;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Rung 1 (#166): the results-grid row mapping must carry the same termination-reason honesty the
 * detail page already has (#118/#105) — a terminated/deleted instance must NOT render
 * indistinguishably from a genuine completion. Direct coverage of {@link SearchService#row}, the
 * package-private seam, mirroring {@code InstanceDetailMappingTest}'s coverage of
 * {@code InstanceDetailService.terminationReason}.
 */
class SearchServiceRowTest {

    private final SearchService service = new SearchService(
            mock(EngineRegistry.class),
            mock(ProcessApiClient.class),
            new InspectorProperties(4, 10, null, null, null, List.of()),
            mock(ProtectedInstanceRepository.class));

    private final EngineConfig engine = TestEngines.engine("engine-a", "http://a");

    private static final InstanceStatusFlags ENDED = new InstanceStatusFlags(true, false, false, false, false);

    @Test
    void aTerminatedInstanceCarriesItsReasonInsteadOfReadingPlainCompleted() {
        Map<String, Object> historic = Map.of(
                "id", "pid-1",
                "state", "EXTERNALLY_TERMINATED",
                "deleteReason", "customer requested cancellation");

        ProcessInstanceRow row = service.row(engine, historic, ENDED, null);

        assertThat(row.status()).isEqualTo(InstanceStatus.COMPLETED); // chip set/facets must not churn
        assertThat(row.terminationReason()).isEqualTo("customer requested cancellation");
    }

    @Test
    void preSixXEngineWithoutStateFallsBackToDeleteReason() {
        Map<String, Object> historic = Map.of("id", "pid-2", "deleteReason", "deleted via legacy API");

        ProcessInstanceRow row = service.row(engine, historic, ENDED, null);

        assertThat(row.terminationReason()).isEqualTo("deleted via legacy API");
    }

    @Test
    void aGenuineCompletionHasNoTerminationReason() {
        Map<String, Object> historic = Map.of("id", "pid-3", "state", "COMPLETED");

        ProcessInstanceRow row = service.row(engine, historic, ENDED, null);

        assertThat(row.status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(row.terminationReason()).isNull();
    }

    @Test
    void aRunningInstanceHasNoTerminationReasonEvenWithAStaleDeleteReasonKey() {
        InstanceStatusFlags active = new InstanceStatusFlags(false, false, false, false, false);
        Map<String, Object> historic = Map.of("id", "pid-4", "deleteReason", "ignored while running");

        ProcessInstanceRow row = service.row(engine, historic, active, null);

        assertThat(row.terminationReason()).isNull();
    }
}
