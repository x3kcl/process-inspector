package io.inspector.heat;

import java.util.List;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Definition heat (#786) on Flowable 6.8 only: the nightly engine-its harness (flowable-6). */
class Heat68IT extends HeatITBase {

    private static final List<Engine> ENGINES =
            List.of(new Engine("engine-a", url("PI_ENGINE_A_PORT", "8081"), true, 13));

    @DynamicPropertySource
    static void registry(DynamicPropertyRegistry registry) {
        register(registry, ENGINES);
    }

    @Override
    List<Engine> engines() {
        return ENGINES;
    }
}
