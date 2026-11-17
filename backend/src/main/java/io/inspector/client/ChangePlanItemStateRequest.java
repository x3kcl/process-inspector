package io.inspector.client;

import java.util.List;

/** The body of a case change-state POST (#720). Stub: the typed record lands with the refactor. */
public record ChangePlanItemStateRequest(
        List<String> activatePlanItemDefinitionIds,
        List<String> moveToAvailablePlanItemDefinitionIds,
        List<String> terminatePlanItemDefinitionIds) {

    public static ChangePlanItemStateRequest of(String action, String definitionId) {
        return new ChangePlanItemStateRequest(null, null, null);
    }
}
