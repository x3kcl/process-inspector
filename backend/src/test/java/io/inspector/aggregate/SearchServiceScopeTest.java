package io.inspector.aggregate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.dto.SearchResponse;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Rung 2 (mock the engine client): S2 read scoping (R-SAFE-17). The load-bearing property is that an
 * engine outside the caller's read scope is NEVER fanned out to — proven via
 * {@code verifyNoInteractions(flowable)} — and is instead labeled on the perEngine honesty envelope
 * rather than silently dropped. The unrestricted ({@code null}) path must not over-filter.
 */
class SearchServiceScopeTest {

    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final ProcessApiClient flowable = mock(ProcessApiClient.class);
    private final InspectorProperties props = new InspectorProperties(4, 10, null, null, null, List.of());
    private final SearchService service =
            new SearchService(registry, flowable, props, mock(ProtectedInstanceRepository.class));

    private final EngineConfig engineA = TestEngines.engineInTenant("engine-a", "http://a", "tenant-a");
    private final EngineConfig engineB = TestEngines.engineInTenant("engine-b", "http://b", "tenant-b");

    private SearchRequest request(List<String> engineIds) {
        return new SearchRequest(
                engineIds,
                List.of(InstanceStatus.ACTIVE),
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
                "startTime",
                50);
    }

    @Test
    void anOutOfScopeEngineIsLabeledAndNeverQueried() {
        when(registry.all()).thenReturn(List.of(engineA, engineB));
        when(registry.resolve("engine-a")).thenReturn(Optional.of(engineA));
        when(registry.resolve("engine-b")).thenReturn(Optional.of(engineB));

        // The caller may read neither engine → both are named, both must be labeled, and ZERO engine
        // requests may fire (the security-critical assertion).
        SearchResponse res = service.search(request(List.of("engine-a", "engine-b")), Set.of());

        verifyNoInteractions(flowable);
        assertThat(res.perEngine().get("engine-a").ok()).isFalse();
        assertThat(res.perEngine().get("engine-a").error()).contains("outside your access scope");
        assertThat(res.perEngine().get("engine-b").error()).contains("outside your access scope");
        assertThat(res.rows()).isEmpty();
    }

    @Test
    void onlyOutOfScopeNamedEnginesAreLabeled() {
        when(registry.all()).thenReturn(List.of(engineA, engineB));
        when(registry.resolve("engine-b")).thenReturn(Optional.of(engineB));

        // engine-a is readable (so it is fanned out — its envelope is whatever the mock engine yields,
        // NOT a scope label); engine-b is out of scope and labeled.
        SearchResponse res = service.search(request(List.of("engine-a", "engine-b")), Set.of("engine-a"));

        assertThat(res.perEngine().get("engine-b").error()).contains("outside your access scope");
        assertThat(res.perEngine().get("engine-a").error()).doesNotContain("outside your access scope");
    }

    @Test
    void unrestrictedNullScopeLabelsNothingAsOutOfScope() {
        when(registry.all()).thenReturn(List.of(engineA, engineB));

        // null = enforcement off: both engines are fanned out, neither is scope-excluded.
        SearchResponse res = service.search(request(List.of("engine-a", "engine-b")), null);

        assertThat(res.perEngine().values())
                .noneMatch(r -> r.error() != null && r.error().contains("access scope"));
    }
}
