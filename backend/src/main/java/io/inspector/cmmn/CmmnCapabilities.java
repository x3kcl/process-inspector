package io.inspector.cmmn;

import io.inspector.action.GuardRefusedException;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineRegistry;
import org.springframework.http.HttpStatus;

/**
 * The single 6.8+ capability gate shared by every CMMN scope/detail service (Case Inspector
 * R-SEM-20). {@code scopeType} (Flowable ≥ 6.8) is the gate: 6.3.1 ships a {@code /cmmn-api}
 * context but it is dead-letter-blind and stateless (spike Q3), so a call there would silently
 * return a wrong view. Refused in the BFF with a ProblemDetail (via {@link GuardRefusedException},
 * mapped by the global advice), never sent blind. Extracted from {@code CmmnScopeService} so
 * Phase-1 (scope facet) and Phase-2 (case detail) refuse identically.
 */
final class CmmnCapabilities {

    private CmmnCapabilities() {}

    /**
     * Refuses unless the engine has answered a health probe AND reports the {@code scopeType}
     * capability (Flowable ≥ 6.8). An unprobed engine is refused (capability-unknown) rather than
     * assumed capable — fail-closed.
     */
    static void requireScopeType(EngineRegistry registry, EngineConfig engine) {
        var health = registry.healthOf(engine.id());
        EngineCapabilities capabilities = health != null ? health.capabilities() : null;
        if (capabilities == null) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "capability-unknown",
                    "Engine '" + engine.id() + "' has not answered a health probe yet — CMMN scope support"
                            + " (Flowable ≥ 6.8) is unverified, so the call is refused rather than sent blind.");
        }
        if (!capabilities.scopeType()) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "capability-unavailable",
                    "Engine '" + engine.id() + "' runs an Unsupported Engine Version for CMMN scope"
                            + " (requires Flowable ≥ 6.8 — older engines are dead-letter-blind on the cmmn"
                            + " context) — refused in the BFF, never a silently wrong view.");
        }
    }
}
