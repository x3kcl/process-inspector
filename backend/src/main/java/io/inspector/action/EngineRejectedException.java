package io.inspector.action;

import java.util.UUID;

/**
 * The engine answered the mutation with an error status: nothing happened engine-side
 * (Flowable rejects atomically), and the engine's own words are quoted (SPEC §6 —
 * "engine rejected (nothing happened, engine's words quoted)").
 */
public class EngineRejectedException extends RuntimeException {

    private final UUID auditId;
    private final int engineStatus;
    private final String engineBody;

    public EngineRejectedException(UUID auditId, int engineStatus, String engineBody) {
        super("The engine rejected the action (HTTP " + engineStatus + "). Nothing happened.");
        this.auditId = auditId;
        this.engineStatus = engineStatus;
        this.engineBody = engineBody;
    }

    public UUID auditId() {
        return auditId;
    }

    public int engineStatus() {
        return engineStatus;
    }

    public String engineBody() {
        return engineBody;
    }
}
