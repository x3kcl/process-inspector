package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The body of POST /runtime/process-instances/{id}/change-state — the token move (#720): cancel
 * the executions at {@code cancelActivityIds}, start fresh ones at {@code startActivityIds}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChangeActivityStateRequest(List<String> cancelActivityIds, List<String> startActivityIds) {}
