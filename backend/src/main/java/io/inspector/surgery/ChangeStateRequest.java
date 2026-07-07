package io.inspector.surgery;

import java.util.List;

/**
 * The change-state (token move) request — SPEC §5 tier 2. {@code sourceActivityIds} are
 * the activities whose executions are canceled, {@code targetActivityIds} where fresh
 * ones start. {@code reason} is always required on EXECUTE (tier-2 discipline, SPEC §6);
 * preview accepts the same body without it.
 */
public record ChangeStateRequest(
        String reason, String ticketId, List<String> sourceActivityIds, List<String> targetActivityIds) {}
