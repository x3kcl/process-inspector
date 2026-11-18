package io.inspector.heat;

import java.util.List;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Definition heat (#786) on Flowable 6.8 ({@code engine-a}) and 7.1 ({@code engine-7}).
 *
 * <p>CI: the {@code migration-findings}-stack integration leg (flowable-6 + flowable-7 + postgres).
 * Locally: {@code COMPOSE_PROFILES=flowable-6,flowable-7 docker compose -f
 * docker/docker-compose.dev.yml up -d}.
 */
class HeatIT extends HeatITBase {

    private static final List<Engine> ENGINES = List.of(
            new Engine("engine-a", url("PI_ENGINE_A_PORT", "8081"), true, 13),
            new Engine("engine-7", url("PI_ENGINE_7_PORT", "8083"), true, 13));

    @DynamicPropertySource
    static void registry(DynamicPropertyRegistry registry) {
        register(registry, ENGINES);
    }

    @Override
    List<Engine> engines() {
        return ENGINES;
    }
}
