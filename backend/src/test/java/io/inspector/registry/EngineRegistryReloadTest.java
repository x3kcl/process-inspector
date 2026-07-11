package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.AlarmThresholds;
import io.inspector.config.InspectorProperties.Auth;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.config.InspectorProperties.Timeouts;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Rung 1: the S3 reload seam on {@link EngineRegistry} (docs/REGISTRY-CRUD.md §4). The map holds
 * enabled AND disabled rows (so a disabled engine still resolves by id), {@link EngineRegistry#all}
 * filters to enabled, and {@link EngineRegistry#reload} atomically swaps the set while preserving
 * health for retained ids.
 */
class EngineRegistryReloadTest {

    private static EngineConfig engine(String id, boolean enabled, String baseUrl) {
        return new EngineConfig(
                id,
                id,
                baseUrl,
                EngineEnvironment.DEV,
                null,
                enabled,
                null,
                null,
                new Auth(Auth.Type.none, null, null, null),
                EngineMode.READ_WRITE,
                new Timeouts(null, null, null),
                null,
                null,
                new AlarmThresholds(null, null, null));
    }

    private static EngineRegistry registryOf(EngineConfig... engines) {
        return new EngineRegistry(new InspectorProperties(4, 10, null, null, List.of(engines)));
    }

    @Test
    void all_and_require_are_enabled_only_but_resolve_finds_a_disabled_engine() {
        EngineRegistry registry = registryOf(
                engine("a", true, "http://a/service"),
                engine("b", true, "http://b/service"),
                engine("c", false, "http://c/service"));

        // Fan-out + operable target: enabled only (unchanged contract).
        assertThat(registry.all()).extracting(EngineConfig::id).containsExactly("a", "b");
        assertThatThrownBy(() -> registry.require("c")).isInstanceOf(ResponseStatusException.class);
        // id→name resolution: disabled rows are still reachable.
        assertThat(registry.resolve("c"))
                .get()
                .satisfies(e -> assertThat(e.enabled()).isFalse());
        assertThat(registry.resolve("nope")).isEmpty();
    }

    @Test
    void allForDisplay_includes_disabled_engines_so_the_dashboard_never_silently_omits_one() {
        // Usability W1#4 / theme T6 (R-SEM-17): a disabled engine renders greyed-with-reason,
        // never disappears. Display surface = every live row; fan-out stays enabled-only.
        EngineRegistry registry = registryOf(
                engine("a", true, "http://a/service"),
                engine("c", false, "http://c/service"),
                engine("b", true, "http://b/service"));

        assertThat(registry.allForDisplay()).extracting(EngineConfig::id).containsExactly("a", "c", "b");
        assertThat(registry.all()).extracting(EngineConfig::id).containsExactly("a", "b");
    }

    @Test
    void reload_swaps_the_live_set_and_updates_config() {
        EngineRegistry registry =
                registryOf(engine("a", true, "http://old-a/service"), engine("b", true, "http://b/service"));

        registry.reload(List.of(engine("a", true, "http://new-a/service"), engine("d", true, "http://d/service")));

        assertThat(registry.all()).extracting(EngineConfig::id).containsExactly("a", "d");
        assertThat(registry.require("a").baseUrl()).isEqualTo("http://new-a/service"); // edited
        assertThatThrownBy(() -> registry.require("b")).isInstanceOf(ResponseStatusException.class); // removed
    }

    @Test
    void reload_preserves_health_for_retained_engines_and_drops_it_for_removed_ones() {
        EngineRegistry registry =
                registryOf(engine("a", true, "http://a/service"), engine("b", true, "http://b/service"));
        EngineHealth aHealth = EngineHealth.unreachable("boom", 123L);
        registry.updateHealth("a", aHealth);

        registry.reload(List.of(engine("a", true, "http://a/service"))); // b removed

        assertThat(registry.healthOf("a")).isEqualTo(aHealth); // retained
        assertThat(registry.healthOf("b").reachable()).isFalse(); // dropped → unknown default
    }
}
