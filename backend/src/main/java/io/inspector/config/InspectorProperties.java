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
        Integer fanoutParallelism, @Valid List<EngineConfig> engines) {
    /** Engine ids are stable slugs used in composite instance IDs (R-SEM-08) — never rename. */
    public static final String ENGINE_ID_PATTERN = "^[a-z0-9][a-z0-9._-]{0,63}$";

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
