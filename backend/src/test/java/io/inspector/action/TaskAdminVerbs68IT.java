package io.inspector.action;

import java.util.List;
import org.springframework.test.context.ActiveProfiles;

/** The task-admin verbs (#763) on Flowable 6.8 only: the nightly engine-its harness (flowable-6). */
@ActiveProfiles("it-actions")
class TaskAdminVerbs68IT extends TaskAdminVerbsITBase {

    @Override
    List<Engine> engines() {
        return List.of(new Engine("engine-a", url("PI_ENGINE_A_PORT", "8081")));
    }
}
