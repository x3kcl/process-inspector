package io.inspector.migration;

import io.inspector.action.GuardRefusedException;
import io.inspector.client.CmmnApiClient;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineRegistry;
import org.springframework.http.HttpStatus;

/**
 * The Flowable 7.x version gate of case migration (#500). By-definition batch, historic-case
 * migration and the business-key {@code contains} cohort filter exist on 7.x only. On an older
 * engine the BFF refuses with ProblemDetail {@code version-gate} before any engine call; the UI
 * greys the same controls with {@link #reason}.
 */
public final class CaseMigrationGate {

    public static final String CODE = "version-gate";

    private CaseMigrationGate() {}

    /** Null when the engine is 7.x or newer. Otherwise the sentence the UI shows next to the greyed control. */
    public static String reason(EngineRegistry registry, EngineConfig engine) {
        var health = registry.healthOf(engine.id());
        String version = health == null ? null : health.version();
        if (CmmnApiClient.flowable7(version)) {
            return null;
        }
        return "needs Flowable 7.x (engine '" + engine.id() + "' is "
                + (version == null || version.isBlank() ? "unknown" : version.trim()) + ")";
    }

    public static void require7(EngineRegistry registry, EngineConfig engine, String what, String why) {
        String reason = reason(registry, engine);
        if (reason != null) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    CODE,
                    what + " is behind the Flowable 7.x version gate: " + reason + ". " + why
                            + ". Refused before any engine call. Nothing happened.");
        }
    }
}
