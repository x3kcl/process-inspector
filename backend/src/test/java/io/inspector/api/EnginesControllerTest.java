package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.AlarmThresholds;
import io.inspector.config.InspectorProperties.Auth;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.config.InspectorProperties.Timeouts;
import io.inspector.dto.EngineDto;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rung 1 (unit-test-patterns): the engines-list display contract for usability W1#4 /
 * theme T6 — engine policy visible at the point of action. The list carries {@code mode}
 * and {@code lifecycle} per engine and includes NON-ACTIVE engines (the dashboard renders
 * them greyed-with-reason; it never silently omits one — R-SEM-17/R-GOV-04 doctrine).
 * Wire-shape/serialization stays in {@link EnginesApiSpringTest}.
 */
class EnginesControllerTest {

    private static EngineConfig engine(String id, boolean enabled, EngineMode mode) {
        return TestEngines.builder(id, "http://" + id + "/flowable-rest/service")
                .name("Engine " + id)
                .enabled(enabled)
                .auth(new Auth(Auth.Type.none, null, null, null))
                .mode(mode)
                .timeouts(new Timeouts(null, null, null))
                .alarmThresholds(new AlarmThresholds(null, null, null))
                .build();
    }

    private static EnginesController controllerOf(EngineConfig... engines) {
        return new EnginesController(new EngineRegistry(new InspectorProperties(4, 10, null, null, List.of(engines))));
    }

    @Test
    void list_includes_disabled_engines_with_their_lifecycle_never_silently_omitting_them() {
        EnginesController controller = controllerOf(
                engine("engine-a", true, EngineMode.READ_WRITE), engine("engine-7", false, EngineMode.READ_WRITE));

        List<EngineDto> list = controller.list();

        assertThat(list).extracting(EngineDto::id).containsExactly("engine-a", "engine-7");
        assertThat(list.get(0).lifecycle()).isEqualTo("active");
        assertThat(list.get(1).lifecycle()).isEqualTo("disabled");
    }

    @Test
    void list_carries_the_engine_mode_so_read_only_policy_is_visible_before_a_refusal() {
        EnginesController controller = controllerOf(engine("engine-ro", true, EngineMode.READ_ONLY));

        assertThat(controller.list()).singleElement().satisfies(dto -> {
            assertThat(dto.mode()).isEqualTo("read-only");
            assertThat(dto.lifecycle()).isEqualTo("active");
        });
    }
}
