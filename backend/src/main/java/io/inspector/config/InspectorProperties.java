package io.inspector.config;

import io.inspector.audit.AuditPayloadMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
        @Valid Snapshot snapshot,
        @Valid Incidents incidents,
        @Valid SelfHeal selfHeal,
        @Valid List<EngineConfig> engines) {
    /** Engine ids are stable slugs used in composite instance IDs (R-SEM-08) — never rename. */
    public static final String ENGINE_ID_PATTERN = "^[a-z0-9][a-z0-9._-]{0,63}$";

    /** superProcessInstanceId chain-walk bound (ARCH §2.3) — lowered in test profiles. */
    public int hierarchyMaxDepthOrDefault() {
        return hierarchyMaxDepth != null ? hierarchyMaxDepth : 10;
    }

    public Triage triageOrDefault() {
        return triage != null ? triage : new Triage(null, null, null, null);
    }

    /**
     * Stage 0 triage knobs (SPEC §4/§9): 20s aggregation cache TTL (thundering-herd
     * protection — spec-pinned default), Refresh bypass throttled to one per 10s, the
     * cap on representative stacktrace fetches used to refine error groups, and the
     * R-BAU-01 acknowledge auto-resurface threshold (an acked group resurfaces once its
     * member count grows PAST the acknowledged baseline by this percentage).
     */
    public record Triage(
            Integer cacheTtlS,
            Integer refreshMinIntervalS,
            Integer stacktraceSampleCap,
            Integer ackResurfaceThresholdPct) {
        public int cacheTtlSOrDefault() {
            return cacheTtlS != null ? cacheTtlS : 20;
        }

        public int refreshMinIntervalSOrDefault() {
            return refreshMinIntervalS != null ? refreshMinIntervalS : 10;
        }

        public int stacktraceSampleCapOrDefault() {
            return stacktraceSampleCap != null ? stacktraceSampleCap : 25;
        }

        public int ackResurfaceThresholdPctOrDefault() {
            return ackResurfaceThresholdPct != null ? ackResurfaceThresholdPct : 20;
        }
    }

    public Bulk bulkOrDefault() {
        return bulk != null ? bulk : new Bulk(null, null, null, null);
    }

    /**
     * Bulk fan-out engine-protection knobs (SPEC §7, v1.x #2): at most {@code enginePermits}
     * in-flight dispatches per engine (shared across concurrent jobs) and a mandatory
     * {@code staggerMs} pause between dispatch STARTS per engine — a 5000-item job must
     * trickle into the target async executor, never slam it.
     *
     * <p>{@code circuitPauseMaxMs}/{@code circuitPausePollMs} (R-SEM-11, issue #101): when an
     * item fast-fails on an OPEN circuit, the dispatcher pauses that ONE item's retry (never the
     * whole engine group) up to {@code circuitPauseMaxMs}, polling the breaker every {@code
     * circuitPausePollMs}. The default ceiling (20s) is a deliberate hair past the "engine"
     * breaker's own {@code wait-duration-in-open-state} (15s, application.yml) — long enough for
     * one HALF_OPEN probe cycle to actually resolve, never an unbounded wait.
     *
     * <p>{@code sseSubscriberCap}/{@code sseCoalesceMs} (issue #301): the live bulk-progress
     * stream ({@link io.inspector.stream.SseHub}) caps live subscribers so a burst of open tabs
     * can't grow the emitter registry unbounded — beyond the cap a subscribe attempt is
     * completed immediately and the browser's own EventSource reconnection logic degrades the
     * UI to polling (OPERATIONS §2 / RUNBOOK §7). Repeat {@code bulk-job} events for the SAME
     * job within {@code sseCoalesceMs} of each other coalesce into one flush — the events are
     * id-only signals the client refetches from, so only the LAST one in a burst need ever ship.
     */
    public record Bulk(
            Integer enginePermits,
            Integer staggerMs,
            Integer circuitPauseMaxMs,
            Integer circuitPausePollMs,
            Integer sseSubscriberCap,
            Integer sseCoalesceMs) {
        public int enginePermitsOrDefault() {
            return enginePermits != null ? enginePermits : 4;
        }

        public int staggerMsOrDefault() {
            return staggerMs != null ? staggerMs : 250;
        }

        public long circuitPauseMaxMsOrDefault() {
            return circuitPauseMaxMs != null ? circuitPauseMaxMs : 20_000L;
        }

        public long circuitPausePollMsOrDefault() {
            return circuitPausePollMs != null ? circuitPausePollMs : 1_000L;
        }

        public int sseSubscriberCapOrDefault() {
            return sseSubscriberCap != null ? sseSubscriberCap : 200;
        }

        public long sseCoalesceMsOrDefault() {
            return sseCoalesceMs != null ? sseCoalesceMs : 250L;
        }

        /**
         * Pre-#301 4-arg convenience — keeps every existing call site (production defaults,
         * tests) on the original shape rather than churning them (unit-test-patterns: no
         * constructor churn). The two new fields default via the {@code OrDefault()} accessors.
         */
        public Bulk(Integer enginePermits, Integer staggerMs, Integer circuitPauseMaxMs, Integer circuitPausePollMs) {
            this(enginePermits, staggerMs, circuitPauseMaxMs, circuitPausePollMs, null, null);
        }
    }

    public Snapshot snapshotOrDefault() {
        return snapshot != null ? snapshot : new Snapshot(null, null, null, null);
    }

    /**
     * v2/M4 job-lane snapshot store knobs (R-BAU-08). The sampler upserts one row per
     * (engine, lane) bucket at {@code sampleInterval}; {@code bucketWidth} ≥ interval is the
     * idempotency grid; {@code retentionDays} (400, revFADP) is the drop-partition horizon.
     * Disabled ({@code enabled=false}) in unit-test profiles so no background poll fires.
     */
    public record Snapshot(Boolean enabled, Duration sampleInterval, Duration bucketWidth, Integer retentionDays) {
        public boolean enabledOrDefault() {
            return enabled == null || enabled;
        }

        public Duration sampleIntervalOrDefault() {
            return sampleInterval != null ? sampleInterval : Duration.ofSeconds(60);
        }

        public Duration bucketWidthOrDefault() {
            return bucketWidth != null ? bucketWidth : Duration.ofSeconds(60);
        }

        public int retentionDaysOrDefault() {
            return retentionDays != null ? retentionDays : 400;
        }
    }

    public Incidents incidentsOrDefault() {
        return incidents != null ? incidents : new Incidents(null, null, null, null, null);
    }

    /**
     * v2 Incident Ledger knobs (R-BAU-10, docs/INCIDENT-LEDGER.md §5). {@code enabled} gates the
     * event-consuming ledger independently of the sampler (sampler off ⇒ both stores idle);
     * {@code quietWindow} is the READ-time "quiet" derivation horizon (never stored);
     * {@code regressionMinCount} is the regression-gate hysteresis (a RESOLVED incident
     * re-fires only at/above this live total, after a post-resolve zero/absent cycle);
     * {@code retentionDays} (400, revFADP — aligned with the snapshot store) is the
     * {@code incident_occurrence} drop-partition horizon; {@code listCap} (issue #308,
     * INCIDENT-LEDGER §6) is the hard server-side ceiling on {@code GET /api/incidents}' bounded
     * (no-pagination-v1) list — an ABSENT {@code window} still means "the whole ledger", but now
     * "the whole ledger up to the cap", never truly unbounded; dropped rows are always the OLDEST
     * by {@code lastSeen} and the response says so via {@code truncated}.
     */
    public record Incidents(
            Boolean enabled, Duration quietWindow, Integer regressionMinCount, Integer retentionDays, Integer listCap) {
        public boolean enabledOrDefault() {
            return enabled == null || enabled;
        }

        public Duration quietWindowOrDefault() {
            return quietWindow != null ? quietWindow : Duration.ofHours(24);
        }

        public int regressionMinCountOrDefault() {
            return regressionMinCount != null ? regressionMinCount : 1;
        }

        public int retentionDaysOrDefault() {
            return retentionDays != null ? retentionDays : 400;
        }

        public int listCapOrDefault() {
            return listCap != null ? listCap : 500;
        }
    }

    public SelfHeal selfHealOrDefault() {
        return selfHeal != null ? selfHeal : new SelfHeal(null, null, null, null);
    }

    /**
     * The RETRYING risk lane's self-heal statistics knobs (RETRYING-RISK-LANE.md §7.1/§10,
     * #351, gated on the locked design #347). {@code enabled} gates ONLY the §4.2 dwell-ticking
     * event listener — reads stay live regardless (an unticked class answers the safe {@code
     * INSUFFICIENT_HISTORY} default), the identical doctrine {@code inspector.incidents.enabled}
     * established for the ledger's own read path. {@code windowDays} (90, ≤ the 400-day
     * occurrence retention) bounds the derive-on-read scan. {@code floor} (10 — measured,
     * RETRYING-RISK-LANE.md §7.1: the smallest n where even a perfect/zero record's Wilson bound
     * clears 0.70/0.30, PLUS one spare observation so the floor-entry state sits inside the
     * hysteresis band rather than on its knife edge) is the minimum unconfounded completed
     * spells before any rate/interval renders. {@code dwellCycles} (10, ~10 minutes at the 60s
     * sampler beat) is the minimum consecutive COMPLETE cycles a newly computed lane must hold
     * before it becomes the DISPLAYED one (§4.2 rule 3).
     */
    public record SelfHeal(Boolean enabled, Integer windowDays, Integer floor, Integer dwellCycles) {
        public boolean enabledOrDefault() {
            return enabled == null || enabled;
        }

        public int windowDaysOrDefault() {
            return windowDays != null ? windowDays : 90;
        }

        public int floorOrDefault() {
            return floor != null ? floor : 10;
        }

        public int dwellCyclesOrDefault() {
            return dwellCycles != null ? dwellCycles : 10;
        }
    }

    /**
     * Pre-self-heal 7-arg convenience constructor (no {@code selfHeal}) → defaults via
     * {@link #selfHealOrDefault()}, so every existing call site (production config binding
     * excepted — that always uses the full YAML-bound shape) keeps compiling unchanged
     * (unit-test-patterns: no constructor churn).
     */
    public InspectorProperties(
            Integer fanoutParallelism,
            Integer hierarchyMaxDepth,
            Triage triage,
            Bulk bulk,
            Snapshot snapshot,
            Incidents incidents,
            List<EngineConfig> engines) {
        this(fanoutParallelism, hierarchyMaxDepth, triage, bulk, snapshot, incidents, null, engines);
    }

    // Multiple constructors → Spring cannot infer the binder; pin it to the canonical one.
    @ConstructorBinding
    public InspectorProperties {
        engines = engines != null ? List.copyOf(engines) : List.of();
        Set<String> seen = new HashSet<>();
        for (EngineConfig engine : engines) {
            if (engine.id() != null && !seen.add(engine.id())) {
                throw new IllegalStateException("Duplicate engine id in registry: " + engine.id());
            }
        }
    }

    /**
     * Incidents-less convenience constructor — the {@code incidents} block is optional (v2
     * R-BAU-10 add, defaults via {@link #incidentsOrDefault()}). Keeps pre-ledger call sites on
     * the 6-arg shape rather than churning them (unit-test-patterns: no constructor churn).
     */
    public InspectorProperties(
            Integer fanoutParallelism,
            Integer hierarchyMaxDepth,
            Triage triage,
            Bulk bulk,
            Snapshot snapshot,
            List<EngineConfig> engines) {
        this(fanoutParallelism, hierarchyMaxDepth, triage, bulk, snapshot, null, engines);
    }

    /**
     * Snapshot-less convenience constructor — the {@code snapshot} block is optional (v2/M4 add,
     * defaults via {@link #snapshotOrDefault()}). Keeps pre-M4 call sites (tests, factories) on
     * the original 5-arg shape rather than churning them (unit-test-patterns: no constructor churn).
     */
    public InspectorProperties(
            Integer fanoutParallelism,
            Integer hierarchyMaxDepth,
            Triage triage,
            Bulk bulk,
            List<EngineConfig> engines) {
        this(fanoutParallelism, hierarchyMaxDepth, triage, bulk, null, null, engines);
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
            @Valid AlarmThresholds alarmThresholds,
            // Per-engine audit-payload minimization (R-AUD-03). Null → redacted (minimization by
            // default); YAML `audit-payload: full|redacted|metadata-only` binds here.
            AuditPayloadMode auditPayload,
            // Per-engine X-Forwarded-User send-side opt-in (M4-CLOSEOUT §2 / S4). Off by default —
            // forwarding employee identity (PII) is permitted only on genuinely-trusted engines;
            // YAML `forward-user: true` binds here. Never relied upon: the BFF audit log is master.
            boolean forwardUser,
            // Per-engine deep-paging depth cap (R-NFR-08, docs/KWAY-PAGING.md): the maximum
            // per-engine offset a k-way-merge cursor may reach. Offset cost is O(offset) PER
            // ENGINE, so the cap is per-engine (a single global cap would multiply load ×fan-out).
            // Null → 5000 (aligned to the filter-bulk cap for one operator mental model; the S0
            // spike could not measure the O(offset) knee at test-safe scale). YAML
            // `deep-paging-max-depth: N` binds here — lowered to e.g. 6 in the S5 config-lowered IT.
            Integer deepPagingMaxDepth,
            // Registry lifecycle state for the DISPLAY surface (usability W1#4, theme T6):
            // draft|probed|probe_failed|active|disabled — authoritative under source=db (the
            // row's lifecycle column via EngineRegistryMapper); null under source=config, where
            // lifecycleOrDefault() derives it from `enabled`. Display metadata only — every
            // operability decision keeps reading `enabled`/require().
            String lifecycle) {

        // @ConfigurationProperties binding is ambiguous once a record has >1 constructor — pin the
        // canonical (18-arg) one as the bind target so the convenience ctors below are ignored.
        @ConstructorBinding
        public EngineConfig {}

        /**
         * Pre-lifecycle 17-arg shape (no {@code lifecycle}) → derived from {@code enabled} via
         * {@link #lifecycleOrDefault()}, so existing factories/tests don't churn
         * (unit-test-patterns: no constructor churn).
         */
        public EngineConfig(
                String id,
                String name,
                String baseUrl,
                EngineEnvironment environment,
                String accentColor,
                boolean enabled,
                String tenantId,
                String telemetryUrlTemplate,
                Auth auth,
                EngineMode mode,
                Timeouts timeouts,
                Integer maxPageSize,
                Integer dlqScanCap,
                AlarmThresholds alarmThresholds,
                AuditPayloadMode auditPayload,
                boolean forwardUser,
                Integer deepPagingMaxDepth) {
            this(
                    id,
                    name,
                    baseUrl,
                    environment,
                    accentColor,
                    enabled,
                    tenantId,
                    telemetryUrlTemplate,
                    auth,
                    mode,
                    timeouts,
                    maxPageSize,
                    dlqScanCap,
                    alarmThresholds,
                    auditPayload,
                    forwardUser,
                    deepPagingMaxDepth,
                    null);
        }

        /**
         * Pre-deep-paging 16-arg shape (no {@code deepPagingMaxDepth}) → the 5000 default, so
         * existing factories/tests and the DB→config registry mapper don't churn
         * (unit-test-patterns: no constructor churn).
         */
        public EngineConfig(
                String id,
                String name,
                String baseUrl,
                EngineEnvironment environment,
                String accentColor,
                boolean enabled,
                String tenantId,
                String telemetryUrlTemplate,
                Auth auth,
                EngineMode mode,
                Timeouts timeouts,
                Integer maxPageSize,
                Integer dlqScanCap,
                AlarmThresholds alarmThresholds,
                AuditPayloadMode auditPayload,
                boolean forwardUser) {
            this(
                    id,
                    name,
                    baseUrl,
                    environment,
                    accentColor,
                    enabled,
                    tenantId,
                    telemetryUrlTemplate,
                    auth,
                    mode,
                    timeouts,
                    maxPageSize,
                    dlqScanCap,
                    alarmThresholds,
                    auditPayload,
                    forwardUser,
                    null,
                    null);
        }

        /**
         * Pre-S2 14-arg shape (no {@code auditPayload}, no {@code forwardUser}) → the redacted
         * default + forwarding off, so existing factories/tests don't churn (unit-test-patterns:
         * no constructor churn).
         */
        public EngineConfig(
                String id,
                String name,
                String baseUrl,
                EngineEnvironment environment,
                String accentColor,
                boolean enabled,
                String tenantId,
                String telemetryUrlTemplate,
                Auth auth,
                EngineMode mode,
                Timeouts timeouts,
                Integer maxPageSize,
                Integer dlqScanCap,
                AlarmThresholds alarmThresholds) {
            this(
                    id,
                    name,
                    baseUrl,
                    environment,
                    accentColor,
                    enabled,
                    tenantId,
                    telemetryUrlTemplate,
                    auth,
                    mode,
                    timeouts,
                    maxPageSize,
                    dlqScanCap,
                    alarmThresholds,
                    null,
                    false);
        }

        /**
         * S2-era 15-arg shape ({@code auditPayload} present, no {@code forwardUser}) → forwarding
         * off. Keeps callers that already thread the audit-payload mode compiling unchanged.
         */
        public EngineConfig(
                String id,
                String name,
                String baseUrl,
                EngineEnvironment environment,
                String accentColor,
                boolean enabled,
                String tenantId,
                String telemetryUrlTemplate,
                Auth auth,
                EngineMode mode,
                Timeouts timeouts,
                Integer maxPageSize,
                Integer dlqScanCap,
                AlarmThresholds alarmThresholds,
                AuditPayloadMode auditPayload) {
            this(
                    id,
                    name,
                    baseUrl,
                    environment,
                    accentColor,
                    enabled,
                    tenantId,
                    telemetryUrlTemplate,
                    auth,
                    mode,
                    timeouts,
                    maxPageSize,
                    dlqScanCap,
                    alarmThresholds,
                    auditPayload,
                    false);
        }

        public AuditPayloadMode auditPayloadOrDefault() {
            return auditPayload != null ? auditPayload : AuditPayloadMode.REDACTED;
        }

        public EngineMode modeOrDefault() {
            return mode != null ? mode : EngineMode.READ_WRITE;
        }

        /**
         * The display lifecycle (W1#4, theme T6): the DB row's lifecycle when present, else
         * derived from {@code enabled} ({@code active}/{@code disabled}) under source=config.
         */
        public String lifecycleOrDefault() {
            return lifecycle != null ? lifecycle : (enabled ? "active" : "disabled");
        }

        public int maxPageSizeOrDefault() {
            return maxPageSize != null ? maxPageSize : 200;
        }

        public int dlqScanCapOrDefault() {
            return dlqScanCap != null ? dlqScanCap : 5000;
        }

        public int deepPagingMaxDepthOrDefault() {
            return deepPagingMaxDepth != null ? deepPagingMaxDepth : 5000;
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
