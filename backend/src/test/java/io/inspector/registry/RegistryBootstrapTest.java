package io.inspector.registry;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.AlarmThresholds;
import io.inspector.config.InspectorProperties.Auth;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.config.InspectorProperties.Timeouts;
import io.inspector.config.RegistryProperties;
import io.inspector.config.RegistryProperties.Source;
import io.inspector.registry.RegistryDrift.DriftReport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Rung 1: the boot-time source resolution DECISION logic (docs/REGISTRY-CRUD.md §4) — config-pin,
 * seed-on-empty, drift-on-nonempty, and (S3) the DB→registry reload — with mocked collaborators so
 * no DB is needed. The end-to-end seed + reload against real Postgres is
 * {@link EngineRegistryStoreIT} / {@link EngineRegistryReloadIT}.
 */
class RegistryBootstrapTest {

    private static EngineConfig engine(String id) {
        return new EngineConfig(
                id,
                id,
                "http://" + id + "/service",
                EngineEnvironment.DEV,
                null,
                true,
                null,
                null,
                new Auth(Auth.Type.none, null, null, null),
                EngineMode.READ_WRITE,
                new Timeouts(null, null, null),
                null,
                null,
                new AlarmThresholds(null, null, null));
    }

    private static InspectorProperties inspector(List<EngineConfig> engines) {
        return new InspectorProperties(4, 10, null, null, engines);
    }

    private static RegistryProperties registryProps(Source source) {
        return new RegistryProperties(source, List.of(), java.util.Set.of());
    }

    @Test
    void config_pinned_touches_nothing() {
        EngineRegistryStore store = Mockito.mock(EngineRegistryStore.class);
        EngineRegistry registry = Mockito.mock(EngineRegistry.class);

        new RegistryBootstrap(registryProps(Source.CONFIG), inspector(List.of(engine("a"))), store, registry).run(null);

        verifyNoInteractions(store);
        verifyNoInteractions(registry); // config-pin keeps the ctor map — no DB reload
    }

    @Test
    void empty_registry_seeds_the_yaml_engines_then_reloads() {
        EngineRegistryStore store = Mockito.mock(EngineRegistryStore.class);
        EngineRegistry registry = Mockito.mock(EngineRegistry.class);
        when(store.isEmpty()).thenReturn(true);
        List<EngineConfig> engines = List.of(engine("a"), engine("b"));

        new RegistryBootstrap(registryProps(Source.DB), inspector(engines), store, registry).run(null);

        verify(store).seedFromConfig(engines);
        verify(store, never()).driftReport(any());
        verify(registry).reload(any()); // DB→registry after the seed
    }

    @Test
    void empty_registry_with_no_yaml_seeds_nothing_but_still_reloads() {
        EngineRegistryStore store = Mockito.mock(EngineRegistryStore.class);
        EngineRegistry registry = Mockito.mock(EngineRegistry.class);
        when(store.isEmpty()).thenReturn(true);

        new RegistryBootstrap(registryProps(Source.DB), inspector(List.of()), store, registry).run(null);

        verify(store, never()).seedFromConfig(any());
        verify(registry).reload(any());
    }

    @Test
    void non_empty_registry_reports_drift_then_reloads_from_db() {
        EngineRegistryStore store = Mockito.mock(EngineRegistryStore.class);
        EngineRegistry registry = Mockito.mock(EngineRegistry.class);
        when(store.isEmpty()).thenReturn(false);
        when(store.driftReport(any())).thenReturn(new DriftReport(List.of("c"), List.of(), List.of()));

        new RegistryBootstrap(registryProps(Source.DB), inspector(List.of(engine("a"))), store, registry).run(null);

        verify(store).driftReport(any());
        verify(store, never()).seedFromConfig(any());
        verify(registry).reload(any());
    }

    @Test
    void a_seed_failure_with_still_empty_registry_is_caught_so_startup_never_crashes() {
        EngineRegistryStore store = Mockito.mock(EngineRegistryStore.class);
        EngineRegistry registry = Mockito.mock(EngineRegistry.class);
        when(store.isEmpty()).thenReturn(true, true);
        when(store.seedFromConfig(any())).thenThrow(new IllegalStateException("audit down"));

        // Must not propagate — fail-closed = boot with an empty registry, retry next boot.
        new RegistryBootstrap(registryProps(Source.DB), inspector(List.of(engine("a"))), store, registry).run(null);
        verify(registry).reload(any()); // reload still runs (empty set) so the registry is consistent
    }

    @Test
    void a_concurrent_seed_race_is_recognized_not_mislabeled_as_empty() {
        EngineRegistryStore store = Mockito.mock(EngineRegistryStore.class);
        EngineRegistry registry = Mockito.mock(EngineRegistry.class);
        // Empty at the gate, but a concurrent instance committed the seed while we were mid-import,
        // so our insert hit the PK and rolled back and the table is now NOT empty.
        when(store.isEmpty()).thenReturn(true, false);
        when(store.seedFromConfig(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup id"));

        new RegistryBootstrap(registryProps(Source.DB), inspector(List.of(engine("a"))), store, registry).run(null);
        verify(registry).reload(any());
    }
}
