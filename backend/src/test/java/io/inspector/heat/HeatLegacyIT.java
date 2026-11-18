package io.inspector.heat;

import java.util.List;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Definition heat (#786) on Flowable 6.3.1, which records no {@code sequenceFlow} rows: coverage
 * {@code NO_FLOW_HISTORY}, node figures still there, the armed one-year timer told from the taken
 * message by timing (DEFINITION-HEATMAP.md §1.1a) and the note saying so.
 *
 * <p>CI: the {@code legacy} integration leg. Locally: {@code docker compose -f
 * docker/docker-compose.dev.yml --profile legacy up -d}.
 */
class HeatLegacyIT extends HeatITBase {

    private static final List<Engine> ENGINES =
            List.of(new Engine("engine-legacy", url("PI_ENGINE_LEGACY_PORT", "8084"), false, 7));

    @DynamicPropertySource
    static void registry(DynamicPropertyRegistry registry) {
        register(registry, ENGINES);
    }

    @Override
    List<Engine> engines() {
        return ENGINES;
    }
}
