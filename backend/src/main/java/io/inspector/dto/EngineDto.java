package io.inspector.dto;

import io.inspector.config.InspectorProperties;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineHealth;
import java.time.Instant;
import java.util.Locale;

/**
 * The Stage 0 health-strip entry for one engine: registry identity + live health,
 * capabilities, job-lane counts and starvation alarms (ARCHITECTURE §3/§4).
 * Never carries secrets or secret refs. An unreachable engine is a normal entry
 * with reachable=false and healthError set — never a failed request.
 */
public record EngineDto(
        String id,
        String name,
        String environment, // "dev" | "test" | "prod"
        String accentColor,
        String mode, // "read-write" | "read-only"
        // Registry lifecycle (W1#4, theme T6): "active" | "disabled" | "draft" | "probed" |
        // "probe_failed" — the display surface renders a non-active engine greyed-with-reason,
        // never silently omitting it (R-SEM-17/R-GOV-04 doctrine).
        String lifecycle,
        String tenantId,
        boolean reachable,
        String engineVersion,
        String lastHealthCheck, // ISO-8601 UTC; null if never probed
        EngineCapabilities capabilities, // null until the first successful probe
        EngineHealth.JobLanes jobLanes, // null when unknown/degraded
        Long oldestExecutableJobAge, // seconds; null when the lane is empty/unknown
        Long overdueTimers, // null when unknown
        String healthError) {

    /** The one registry-config + live-health mapping, shared by /api/engines and /api/triage. */
    public static EngineDto from(InspectorProperties.EngineConfig config, EngineHealth health) {
        return new EngineDto(
                config.id(),
                config.name(),
                config.environment().name().toLowerCase(Locale.ROOT),
                config.accentColor(),
                config.modeOrDefault().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                config.lifecycleOrDefault(),
                config.tenantId(),
                health.reachable(),
                health.version(),
                health.checkedAtEpochMs() > 0
                        ? Instant.ofEpochMilli(health.checkedAtEpochMs()).toString()
                        : null,
                health.capabilities(),
                health.jobLanes(),
                health.oldestExecutableJobAgeSec(),
                health.overdueTimers(),
                health.error());
    }
}
