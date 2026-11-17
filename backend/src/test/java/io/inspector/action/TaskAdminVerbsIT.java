package io.inspector.action;

import java.util.List;
import org.springframework.test.context.ActiveProfiles;

/**
 * The task-admin verbs (#763) on BOTH Flowable 6.8 ({@code engine-a}) and 7.1 ({@code engine-7}).
 *
 * <p>CI: the {@code migration-findings}-stack integration leg (flowable-6 + flowable-7 + postgres).
 * Locally: {@code COMPOSE_PROFILES=flowable-6,flowable-7,postgres docker compose -f
 * docker/docker-compose.dev.yml up -d}.
 */
@ActiveProfiles("it-findings")
class TaskAdminVerbsIT extends TaskAdminVerbsITBase {

    @Override
    List<Engine> engines() {
        return List.of(
                new Engine("engine-a", url("PI_ENGINE_A_PORT", "8081")),
                new Engine("engine-7", url("PI_ENGINE_7_PORT", "8083")));
    }
}
