package io.inspector.registry;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.client.FlowableEngineClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Rung 1: the {@code AFTER_COMMIT} reload listener orchestration (docs/REGISTRY-CRUD.md §4) —
 * reload → evict → reprobe in that order, and a reload/DB hiccup is swallowed (never rethrown) so
 * it can't fail an already-committed write.
 */
class RegistryReloadListenerTest {

    @Test
    void reloads_then_evicts_then_reprobes_in_order() {
        EngineRegistry registry = mock(EngineRegistry.class);
        EngineRegistryStore store = mock(EngineRegistryStore.class);
        FlowableEngineClient flowable = mock(FlowableEngineClient.class);
        EngineHealthService health = mock(EngineHealthService.class);
        when(store.findLive()).thenReturn(List.of());

        new RegistryReloadListener(registry, store, flowable, health)
                .onRegistryChanged(new RegistryChangedEvent("eng"));

        InOrder order = inOrder(registry, flowable, health);
        order.verify(registry).reload(List.of());
        order.verify(flowable).evict("eng");
        order.verify(health).reprobe("eng");
    }

    @Test
    void a_reload_failure_is_swallowed_so_the_committed_write_never_fails() {
        EngineRegistry registry = mock(EngineRegistry.class);
        EngineRegistryStore store = mock(EngineRegistryStore.class);
        FlowableEngineClient flowable = mock(FlowableEngineClient.class);
        EngineHealthService health = mock(EngineHealthService.class);
        when(store.findLive()).thenThrow(new RuntimeException("db blip"));

        // Must NOT throw — a reload hiccup cannot roll back / fail an already-committed registry write.
        new RegistryReloadListener(registry, store, flowable, health)
                .onRegistryChanged(new RegistryChangedEvent("eng"));
    }

    @Test
    void an_evict_failure_is_also_swallowed() {
        EngineRegistry registry = mock(EngineRegistry.class);
        EngineRegistryStore store = mock(EngineRegistryStore.class);
        FlowableEngineClient flowable = mock(FlowableEngineClient.class);
        EngineHealthService health = mock(EngineHealthService.class);
        when(store.findLive()).thenReturn(List.of());
        doThrow(new RuntimeException("evict boom")).when(flowable).evict("eng");

        new RegistryReloadListener(registry, store, flowable, health)
                .onRegistryChanged(new RegistryChangedEvent("eng"));
        verify(registry).reload(List.of()); // reload still ran before the evict blew up
    }
}
