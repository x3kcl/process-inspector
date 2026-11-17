package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.config.InspectorProperties.AlarmThresholds;
import io.inspector.config.InspectorProperties.Auth;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.config.InspectorProperties.Timeouts;
import io.inspector.registry.RegistryDrift.DriftReport;
import io.inspector.support.TestEngines;
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
        return TestEngines.builder(id, baseUrl)
                .environment(env)
                .enabled(enabled)
                .auth(Auth.none())
                .mode(mode)
                .timeouts(new Timeouts(null, null, null))
                .alarmThresholds(new AlarmThresholds(null, null, null))
                .build();
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

    @Test
    void summary_names_each_differing_field_and_never_leaks_a_credential() {
        Auth dbAuth =
                new Auth(Auth.Type.basic, "u", "PW_REF_DB", "TOK_DB_VALUE", null, null, null, null, null, null, null);
        Auth yamlAuth = new Auth(
                Auth.Type.basic, "u", "PW_REF_YAML", "TOK_YAML_VALUE", null, null, null, null, null, null, null);
        EngineConfig db = TestEngines.builder("engine-7", "http://old/service")
                .environment(EngineEnvironment.DEV)
                .auth(dbAuth)
                .build();
        EngineConfig yaml = TestEngines.builder("engine-7", "http://new/service")
                .environment(EngineEnvironment.PROD)
                .auth(yamlAuth)
                .build();

        String summary = RegistryDrift.compute(List.of(yaml), List.of(db)).summary();

        assertThat(summary)
                .contains("engine-7", "baseUrl", "http://new/service", "http://old/service")
                .contains("environment", "authPasswordRef", "PW_REF_YAML", "PW_REF_DB")
                .contains("authTokenRef", "<differs>")
                .doesNotContain("TOK_YAML_VALUE", "TOK_DB_VALUE");
    }
}
