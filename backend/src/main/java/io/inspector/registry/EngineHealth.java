package io.inspector.registry;

/**
 * Mutable runtime state per engine — populated by {@link EngineHealthService}, never by
 * config. This is the Stage 0 health-strip payload: reachability, version, capabilities,
 * the four job-lane counts and the two executor-starvation alarms (ARCHITECTURE §3).
 *
 * Degrade-not-blank: a probe that reaches the engine but fails a later leg keeps
 * {@code reachable=true} with the lane fields null and {@code error} set.
 */
public record EngineHealth(
        boolean reachable,
        String version,
        String error,
        long checkedAtEpochMs,
        EngineCapabilities capabilities,
        JobLanes jobLanes,
        Long oldestExecutableJobAgeSec, // null when the executable lane is empty/unknown
        Long overdueTimers, // timers past due beyond the grace period; null when unknown
        Long oldestHistoryJobAgeSec, // null when the history lane is empty or the route is absent
        String adviceLevel, // OK | WARN | CRIT; null before the first advice pass
        String advice,
        Long startedCurrent, // instances started in the current snapshot window; null = no history yet
        Long startedReference) {
    /** The four job queues plus history jobs when the engine exposes that route. Null history = not available. */
    public record JobLanes(long executable, long timer, long suspended, long deadletter, Long history) {
        public JobLanes(long executable, long timer, long suspended, long deadletter) {
            this(executable, timer, suspended, deadletter, null);
        }
    }

    /** Pre-#484 call sites. The new legs stay null. */
    public EngineHealth(
            boolean reachable,
            String version,
            String error,
            long checkedAtEpochMs,
            EngineCapabilities capabilities,
            JobLanes jobLanes,
            Long oldestExecutableJobAgeSec,
            Long overdueTimers) {
        this(
                reachable,
                version,
                error,
                checkedAtEpochMs,
                capabilities,
                jobLanes,
                oldestExecutableJobAgeSec,
                overdueTimers,
                null,
                null,
                null,
                null,
                null);
    }

    public static EngineHealth unknown() {
        return new EngineHealth(false, null, "not probed yet", 0, null, null, null, null);
    }

    public static EngineHealth unreachable(String error, long nowEpochMs) {
        return new EngineHealth(false, null, error, nowEpochMs, null, null, null, null);
    }
}
