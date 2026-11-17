package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * The body of PUT /repository/process-definitions/{id} — {@code suspend} or {@code activate},
 * optionally cascading to the definition's instances (#720). {@code date} is OMITTED when null:
 * only a scheduled suspend / activate carries it (#483 S6).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProcessDefinitionActionRequest(String action, boolean includeProcessInstances, String date) {

    public ProcessDefinitionActionRequest {
        Objects.requireNonNull(action, "action");
    }
}
