package io.inspector.client;

import java.util.List;

/** The body of POST /runtime/process-instances/{id}/change-state. RED stub (#720): carries nothing yet. */
public record ChangeActivityStateRequest(List<String> cancelActivityIds, List<String> startActivityIds) {

    public ChangeActivityStateRequest {
        cancelActivityIds = null;
        startActivityIds = null;
    }
}
