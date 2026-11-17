package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The body of POST …/cmmn-runtime/case-instances/{id}/change-state (#720): ONE plan-item
 * definition id in exactly one of the three lists. The lists that do not apply are OMITTED.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChangePlanItemStateRequest(
        List<String> activatePlanItemDefinitionIds,
        List<String> moveToAvailablePlanItemDefinitionIds,
        List<String> terminatePlanItemDefinitionIds) {

    /** {@code action} is activate, available or terminate; a null id is refused, as the map body did. */
    public static ChangePlanItemStateRequest of(String action, String definitionId) {
        List<String> ids = List.of(definitionId);
        return switch (action) {
            case "activate" -> new ChangePlanItemStateRequest(ids, null, null);
            case "available" -> new ChangePlanItemStateRequest(null, ids, null);
            case "terminate" -> new ChangePlanItemStateRequest(null, null, ids);
            default -> throw new IllegalArgumentException(action);
        };
    }
}
