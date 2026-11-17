package io.inspector.client;

/** The body of PUT /repository/process-definitions/{id}. RED stub (#720): carries nothing yet. */
public record ProcessDefinitionActionRequest(String action, boolean includeProcessInstances, String date) {

    public ProcessDefinitionActionRequest {
        action = null;
        includeProcessInstances = false;
        date = null;
    }
}
