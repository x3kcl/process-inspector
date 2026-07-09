package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.config.InspectorProperties.AlarmThresholds;
import io.inspector.config.InspectorProperties.Auth;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.config.InspectorProperties.Timeouts;
import io.inspector.registry.RegistryDrift.DriftReport;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: the DB-vs-YAML drift computation (docs/REGISTRY-CRUD.md §4). When DB is authoritative and
 * YAML is ignored, drift must be REPORTED (added/removed/changed), never silent — so an ignored
 * {@code prod.yaml} edit is visible.
 */
class RegistryDriftTest {

    private static EngineConfig cfg(
            String id, String baseUrl, EngineEnvironment env, boolean enabled, EngineMode mode) {
        return new EngineConfig(
                id,
                id,
                baseUrl,
                env,
                null,
                enabled,
                null,
                null,
                new Auth(Auth.Type.none, null, null, null),
                mode,
                new Timeouts(null, null, null),
                null,
                null,
                new AlarmThresholds(null, null, null));
    }

    private static EngineConfig cfg(String id) {
        return cfg(id, "http://" + id + "/service", EngineEnvironment.DEV, true, EngineMode.READ_WRITE);
    }

    @Test
    void no_difference_reports_empty() {
        DriftReport drift = RegistryDrift.compute(List.of(cfg("a"), cfg("b")), List.of(cfg("a"), cfg("b")));
        assertThat(drift.isEmpty()).isTrue();
    }

    @Test
    void yaml_add_and_remove_are_flagged() {
        // YAML has a,c ; DB has a,b → c added-in-yaml (ignored), b removed-from-yaml (still in DB).
        DriftReport drift = RegistryDrift.compute(List.of(cfg("a"), cfg("c")), List.of(cfg("a"), cfg("b")));
        assertThat(drift.added()).containsExactly("c");
        assertThat(drift.removed()).containsExactly("b");
        assertThat(drift.changed()).isEmpty();
        assertThat(drift.isEmpty()).isFalse();
    }

    @Test
    void material_config_change_is_flagged_but_immaterial_is_not() {
        EngineConfig dbA = cfg("a", "http://old/service", EngineEnvironment.DEV, true, EngineMode.READ_WRITE);
        EngineConfig yamlA = cfg("a", "http://new/service", EngineEnvironment.DEV, true, EngineMode.READ_WRITE);
        assertThat(RegistryDrift.compute(List.of(yamlA), List.of(dbA)).changed())
                .containsExactly("a");

        // A mode flip is also material.
        EngineConfig yamlModeFlip = cfg("a", "http://old/service", EngineEnvironment.DEV, true, EngineMode.READ_ONLY);
        assertThat(RegistryDrift.compute(List.of(yamlModeFlip), List.of(dbA)).changed())
                .containsExactly("a");

        // Same material config ⇒ not drift (identical rows).
        assertThat(RegistryDrift.compute(List.of(dbA), List.of(dbA)).isEmpty()).isTrue();
    }
}
