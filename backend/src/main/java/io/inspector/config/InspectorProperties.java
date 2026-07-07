package io.inspector.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds the Engine Registry from application.yml (see docs/ARCHITECTURE.md §3).
 * Secrets are referenced by env-var NAME (passwordRef/tokenRef) and resolved at
 * client-build time — never stored here, never serialized to the UI.
 */
@Validated
@ConfigurationProperties(prefix = "inspector")
public record InspectorProperties(
        Integer fanoutParallelism,
        Integer hierarchyMaxDepth,
        @Valid Triage triage,
        @Valid Bulk bulk,
        @Valid List<EngineConfig> engines) {
    /** Engine ids are stable slugs used in composite instance IDs (R-SEM-08) — never rename. */
    public static final String ENGINE_ID_PATTERN = "^[a-z0-9][a-z0-9._-]{0,63}$";

    /** superProcessInstanceId chain-walk bound (ARCH §2.3) — lowered in test profiles. */
    public int hierarchyMaxDepthOrDefault() {
        return hierarchyMaxDepth != null ? hierarchyMaxDepth : 10;
    }

    public Triage triageOrDefault() {
        return triage != null ? triage : new Triage(null, null, null);
    }

    /**
     * Stage 0 triage knobs (SPEC §4/§9): 20s aggregation cache TTL (thundering-herd
     * protection — spec-pinned default), Refresh bypass throttled to one per 10s, and
     * the cap on representative stacktrace fetches used to refine error groups.
     */
    public record Triage(Integer cacheTtlS, Integer refreshMinIntervalS, Integer stacktraceSampleCap) {
        public int cacheTtlSOrDefault() {
            return cacheTtlS != null ? cacheTtlS : 20;
        }

        public int refreshMinIntervalSOrDefault() {
            return refreshMinIntervalS != null ? refreshMinIntervalS : 10;
        }

        public int stacktraceSampleCapOrDefault() {
            return stacktraceSampleCap != null ? stacktraceSampleCap : 25;
        }
    }

    public Bulk bulkOrDefault() {
        return bulk != null ? bulk : new Bulk(null, null);
    }

    /**
     * Bulk fan-out engine-protection knobs (SPEC §7, v1.x #2): at most {@code enginePermits}
     * in-flight dispatches per engine (shared across concurrent jobs) and a mandatory
     * {@code staggerMs} pause between dispatch STARTS per engine — a 5000-item job must
     * trickle into the target async executor, never slam it.
     */
    public record Bulk(Integer enginePermits, Integer staggerMs) {
        public int enginePermitsOrDefault() {
            return enginePermits != null ? enginePermits : 4;
        }

        public int staggerMsOrDefault() {
            return staggerMs != null ? staggerMs : 250;
        }
    }

    public InspectorProperties {
        engines = engines != null ? List.copyOf(engines) : List.of();
        Set<String> seen = new HashSet<>();
        for (EngineConfig engine : engines) {
            if (engine.id() != null && !seen.add(engine.id())) {
                throw new IllegalStateException("Duplicate engine id in registry: " + engine.id());
            }
        }
    }

    public record EngineConfig(
            @NotBlank @Pattern(regexp = ENGINE_ID_PATTERN, message = "engine id must match " + ENGINE_ID_PATTERN)
            String id,

            String name,
            @NotBlank String baseUrl,
            @NotNull EngineEnvironment environment,
            String accentColor,
            boolean enabled,
            String tenantId,
            // OPTIONAL APM/logs deep-link template (SPEC §4): {processInstanceId},
            // {executionId}, {businessKey}, {failureTime} placeholders. Absent → no link.
            String telemetryUrlTemplate,
            @Valid Auth auth,
            EngineMode mode,
            @Valid Timeouts timeouts,
            Integer maxPageSize,
            Integer dlqScanCap,
            @Valid AlarmThresholds alarmThresholds) {
        public EngineMode modeOrDefault() {
            return mode != null ? mode : EngineMode.READ_WRITE;
        }

        public int maxPageSizeOrDefault() {
            return maxPageSize != null ? maxPageSize : 200;
        }

        public int dlqScanCapOrDefault() {
            return dlqScanCap != null ? dlqScanCap : 5000;
        }

        public Timeouts timeoutsOrDefault() {
            return timeouts != null ? timeouts : new Timeouts(null, null, null);
        }

        public AlarmThresholds alarmsOrDefault() {
            return alarmThresholds != null ? alarmThresholds : new AlarmThresholds(null, null, null);
        }
    }

    /** Drives the env color band and guard strictness — semantics live here, not in accentColor. */
    public enum EngineEnvironment {
        DEV,
        TEST,
        PROD
    }

    /** Rollout ramp (R-GOV-04): READ_ONLY engines reject every mutating verb in the BFF. */
    public enum EngineMode {
        READ_WRITE,
        READ_ONLY
    }

    public record Auth(Type type, String username, String passwordRef, String tokenRef) {
        public enum Type {
            basic,
            bearer,
            none
        }
    }

    public record Timeouts(Integer connectMs, Integer readMs, Integer writeMs) {
        public int connect() {
            return connectMs != null ? connectMs : 2000;
        }

        public int read() {
            return readMs != null ? readMs : 10000;
        }
        /** Budget for MUTATING calls (R-NFR-07); defaults to the read budget. */
        public int write() {
            return writeMs != null ? writeMs : read();
        }
    }

    /** Executor-starvation alarm knobs (R-NFR-04), per-engine overridable. */
    public record AlarmThresholds(Integer oldestJobWarnMin, Integer oldestJobCritMin, Integer overdueTimerGraceS) {
        public int oldestJobWarnMinOrDefault() {
            return oldestJobWarnMin != null ? oldestJobWarnMin : 5;
        }

        public int oldestJobCritMinOrDefault() {
            return oldestJobCritMin != null ? oldestJobCritMin : 15;
        }

        public int overdueTimerGraceSOrDefault() {
            return overdueTimerGraceS != null ? overdueTimerGraceS : 60;
        }
    }
}
