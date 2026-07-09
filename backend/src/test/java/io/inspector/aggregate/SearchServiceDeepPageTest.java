package io.inspector.aggregate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.inspector.client.FlowableEngineClient;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Rung 2 (mock the engine client): the deep-paging DoS ceiling (R-NFR-08). A crafted cursor whose
 * per-engine offset exceeds the depth cap must be refused BEFORE any engine is touched — the test
 * that decides whether the inbound bound-check (not HMAC) is sufficient. Asserts via
 * {@code verifyNoInteractions} that ZERO engine requests fire.
 */
class SearchServiceDeepPageTest {

    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final FlowableEngineClient flowable = mock(FlowableEngineClient.class);
    private final InspectorProperties props = new InspectorProperties(4, 10, null, null, null, List.of());
    private final SearchService service =
            new SearchService(registry, flowable, props, mock(io.inspector.audit.ProtectedInstanceRepository.class));

    private SearchRequest request(String engineId) {
        return new SearchRequest(
                List.of(engineId),
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
    void aCraftedOverCapOffsetIsRefusedBeforeAnyFanOut() {
        EngineConfig engine = TestEngines.engine("engine-a", "http://localhost:1"); // default cap 5000
        when(registry.all()).thenReturn(List.of(engine));

        SearchRequest req = request("engine-a");
        // A forged cursor with the CORRECT filter fingerprint but an absurd offset — the hash binds,
        // so only the inbound bound-check can stop it.
        PagingCursor forged = new PagingCursor(
                PagingCursor.VERSION,
                123L, // recent enough given the TTL is generous vs a synthetic clock; see note below
                PagingCursor.filterHash(req),
                "startTime",
                "desc",
                Map.of("engine-a", 9_999_999),
                null,
                List.of());

        // now = issuedAt so the TTL check passes and we exercise the OFFSET bound specifically.
        assertThatThrownBy(() -> service.deepPage(req, forged.encode(), 123L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");

        verifyNoInteractions(flowable); // the load-bearing assertion: no O(huge-offset) scan ever fired
    }

    @Test
    void garbageCursorIsAlso400WithoutFanOut() {
        when(registry.all()).thenReturn(List.of(TestEngines.engine("engine-a", "http://localhost:1")));
        assertThatThrownBy(() -> service.deepPage(request("engine-a"), "!!!not-a-cursor!!!", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(flowable);
    }

    @Test
    void aFailedOnlySearchRefusesDeepPagingBeforeFanOut() {
        when(registry.all()).thenReturn(List.of(TestEngines.engine("engine-a", "http://localhost:1")));
        SearchRequest failedOnly = new SearchRequest(
                List.of("engine-a"),
                List.of(InstanceStatus.FAILED),
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
        assertThatThrownBy(() -> service.deepPage(failedOnly, null, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not available for FAILED");
        verifyNoInteractions(flowable);
    }
}
