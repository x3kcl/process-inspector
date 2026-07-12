package io.inspector.dto;

import java.util.List;

/**
 * {@code GET /api/diag} (issue #96, OPERATIONS.md §2 — ADMIN-gated): the "what's going on right
 * now" snapshot RUNBOOK.md points an operator at before diving into logs/Prometheus — breaker
 * states, cache ages, bulk-dispatch permit saturation, and the last few engine-call failures with
 * their correlationIds (cross-referencing straight into the log stream / audit trail).
 */
public record DiagResponse(
        String asOf,
        List<BreakerStatus> breakers,
        List<CacheStatus> caches,
        List<PermitStatus> bulkPermits,
        List<RecentError> recentErrors,
        BuildInfo build) {

    /** One resilience4j circuit breaker instance — {@code engineId[:leg]}, per {@code CallPriority}. */
    public record BreakerStatus(String instanceName, String state) {}

    /** A named application-level cache; {@code ageSeconds} is null before it's been populated once. */
    public record CacheStatus(String name, Long ageSeconds) {}

    /** One engine's bulk-dispatch permit pool (populated lazily — only engines dispatched-to this process). */
    public record PermitStatus(String engineId, int available, int total) {}

    public record RecentError(
            String at, String engineId, String leg, String errorClass, String message, String correlationId) {}

    /** Absent when the app wasn't built via {@code mvn package} (e.g. a raw test-compile run). */
    public record BuildInfo(String version, String artifact, String time) {}
}
