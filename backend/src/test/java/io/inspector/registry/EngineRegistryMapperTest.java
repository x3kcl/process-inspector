package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.config.InspectorProperties.AlarmThresholds;
import io.inspector.config.InspectorProperties.Auth;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.config.InspectorProperties.Timeouts;
import io.inspector.support.TestEngines;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Rung 1 (unit-test-patterns): the DB row ↔ {@link EngineConfig} seam (docs/REGISTRY-CRUD.md §10).
 * The DB-authoritative path must produce the exact {@code EngineConfig} shape the YAML path does,
 * so the whole system updates for free when S3 reads the store.
 */
class EngineRegistryMapperTest {

    private static final Instant NOW = Instant.parse("2026-07-09T10:00:00Z");

    private static EngineConfig engine(String id, boolean enabled, EngineMode mode, EngineEnvironment env, Auth auth) {
        return TestEngines.builder(id, "http://host/flowable-rest/service")
                .name("Name " + id)
                .environment(env)
                .accentColor("#123456")
                .enabled(enabled)
                .tenantId("tenant-1")
                .telemetryUrlTemplate("https://apm/{processInstanceId}")
                .auth(auth)
                .mode(mode)
                .timeouts(new Timeouts(1000, 5000, 7000))
                .maxPageSize(150)
                .dlqScanCap(999)
                .alarmThresholds(new AlarmThresholds(3, 9, 42))
                .build();
    }

    @Test
    void enabled_engine_seeds_active_and_round_trips_to_the_same_config() {
        EngineConfig yaml = engine(
                "orders-prod",
                true,
                EngineMode.READ_WRITE,
                EngineEnvironment.PROD,
                new Auth(Auth.Type.basic, "svc", "ENGINE_ORDERS_PASSWORD", null));

        EngineRegistryRow row = EngineRegistryMapper.toSeedRow(yaml, NOW);
        assertThat(row.getLifecycle()).isEqualTo("active");
        assertThat(row.getMode()).isEqualTo("read-write");
        assertThat(row.getEnvironment()).isEqualTo("prod");
        assertThat(row.getSource()).isEqualTo("yaml-seed");
        assertThat(row.getPasswordRef()).isEqualTo("ENGINE_ORDERS_PASSWORD"); // NAME only, never a value
        assertThat(row.getCreatedAt()).isEqualTo(NOW);

        EngineConfig back = EngineRegistryMapper.toEngineConfig(row);
        assertThat(back.id()).isEqualTo("orders-prod");
        assertThat(back.enabled()).isTrue();
        assertThat(back.environment()).isEqualTo(EngineEnvironment.PROD);
        assertThat(back.modeOrDefault()).isEqualTo(EngineMode.READ_WRITE);
        assertThat(back.baseUrl()).isEqualTo("http://host/flowable-rest/service");
        assertThat(back.auth().type()).isEqualTo(Auth.Type.basic);
        assertThat(back.auth().passwordRef()).isEqualTo("ENGINE_ORDERS_PASSWORD");
        assertThat(back.timeoutsOrDefault().connect()).isEqualTo(1000);
        assertThat(back.dlqScanCapOrDefault()).isEqualTo(999);
        assertThat(back.alarmsOrDefault().oldestJobCritMinOrDefault()).isEqualTo(9);
    }

    @Test
    void disabled_engine_seeds_disabled_lifecycle_but_stays_a_live_row() {
        EngineConfig yaml = engine(
                "engine-7",
                false,
                EngineMode.READ_ONLY,
                EngineEnvironment.DEV,
                new Auth(Auth.Type.none, null, null, null));

        EngineRegistryRow row = EngineRegistryMapper.toSeedRow(yaml, NOW);
        assertThat(row.getLifecycle()).isEqualTo("disabled");
        assertThat(row.getMode()).isEqualTo("read-only");
        assertThat(row.getRemovedAt()).isNull(); // disabled ≠ tombstoned
        assertThat(row.getAuthType()).isEqualTo("none");

        EngineConfig back = EngineRegistryMapper.toEngineConfig(row);
        assertThat(back.enabled()).isFalse();
        assertThat(back.modeOrDefault()).isEqualTo(EngineMode.READ_ONLY);
        assertThat(back.auth().type()).isEqualTo(Auth.Type.none);
        // W1#4: the true lifecycle survives the row→config seam (display honesty, theme T6).
        assertThat(back.lifecycleOrDefault()).isEqualTo("disabled");
    }

    @Test
    void onboarding_lifecycles_pass_through_unflattened() {
        // A draft/probe_failed engine must not impersonate "disabled" on the display surface —
        // the row's lifecycle string flows through verbatim (W1#4, R-SEM-17 greyed-with-REASON).
        EngineRegistryRow row = EngineRegistryMapper.toSeedRow(
                engine("newbie", false, EngineMode.READ_ONLY, EngineEnvironment.DEV, null), NOW);
        row.setLifecycle("probe_failed");

        assertThat(EngineRegistryMapper.toEngineConfig(row).lifecycleOrDefault())
                .isEqualTo("probe_failed");
    }

    @Test
    void config_source_lifecycle_derives_from_enabled_when_absent() {
        // Under source=config there is no lifecycle column: enabled ⇒ active, disabled ⇒ disabled.
        EngineConfig enabled = engine("up", true, EngineMode.READ_WRITE, EngineEnvironment.DEV, null);
        EngineConfig disabled = engine("down", false, EngineMode.READ_WRITE, EngineEnvironment.DEV, null);
        assertThat(enabled.lifecycleOrDefault()).isEqualTo("active");
        assertThat(disabled.lifecycleOrDefault()).isEqualTo("disabled");
    }

    @Test
    void null_auth_seeds_auth_type_none() {
        EngineConfig yaml = engine("plain", true, EngineMode.READ_WRITE, EngineEnvironment.DEV, null);

        EngineRegistryRow row = EngineRegistryMapper.toSeedRow(yaml, NOW);
        assertThat(row.getAuthType()).isEqualTo("none");
        assertThat(row.getPasswordRef()).isNull();
        assertThat(EngineRegistryMapper.toEngineConfig(row).auth().type()).isEqualTo(Auth.Type.none);
    }
}
