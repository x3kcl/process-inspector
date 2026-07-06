package io.inspector.dto;

import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineHealth;

/**
 * The Stage 0 health-strip entry for one engine: registry identity + live health,
 * capabilities, job-lane counts and starvation alarms (ARCHITECTURE §3/§4).
 * Never carries secrets or secret refs. An unreachable engine is a normal entry
 * with reachable=false and healthError set — never a failed request.
 */
public record EngineDto(
        String id,
        String name,
        String environment,               // "dev" | "test" | "prod"
        String accentColor,
        String mode,                      // "read-write" | "read-only"
        String tenantId,
        boolean reachable,
        String engineVersion,
        String lastHealthCheck,           // ISO-8601 UTC; null if never probed
        EngineCapabilities capabilities,  // null until the first successful probe
        EngineHealth.JobLanes jobLanes,   // null when unknown/degraded
        Long oldestExecutableJobAge,      // seconds; null when the lane is empty/unknown
        Long overdueTimers,               // null when unknown
        String healthError
) {}
