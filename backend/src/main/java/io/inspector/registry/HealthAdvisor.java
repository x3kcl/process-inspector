package io.inspector.registry;

/**
 * Fleet verdict over sampled health signals (#511). A worse level shows immediately. A quieter
 * level waits for two consecutive samples so one good probe does not clear a warning.
 */
public final class HealthAdvisor {

    public enum Level {
        OK,
        WARN,
        CRIT
    }

    /** What this probe measured, before hysteresis. */
    public record Signal(Level level, String text) {}

    /** What the strip is showing, plus how many quieter probes are in a row. */
    public record Hold(Level level, String text, int quietStreak) {}

    static final long AGE_WARN_SECONDS = 300;

    private HealthAdvisor() {}

    public static Signal raw(EngineHealth health, Long startedCurrent, Long startedReference) {
        if (health == null || !health.reachable()) {
            return new Signal(Level.CRIT, "Engine unreachable. Check the engine process, then BFF egress.");
        }
        StringBuilder reasons = new StringBuilder();
        if (over(health.oldestExecutableJobAgeSec())) {
            reasons.append(
                    "Oldest executable job is over 5 minutes. Escalate to the engine owner: executor starvation. ");
        }
        if (health.overdueTimers() != null && health.overdueTimers() > 0) {
            reasons.append("Overdue timers. The timer executor is behind. ");
        }
        if (over(health.oldestHistoryJobAgeSec())) {
            reasons.append("History backlog is over 5 minutes. Async history is falling behind. ");
        }
        if (startedCurrent != null
                && startedReference != null
                && startedReference > 0
                && startedCurrent * 2 < startedReference) {
            reasons.append("Instance starts dropped versus the recent reference window. ");
        }
        if (reasons.isEmpty()) {
            return new Signal(Level.OK, "All sampled signals are inside their thresholds.");
        }
        return new Signal(Level.WARN, reasons.toString().trim());
    }

    public static Hold step(Hold previous, Signal raw) {
        if (previous == null || raw.level().ordinal() >= previous.level().ordinal()) {
            return new Hold(raw.level(), raw.text(), 0);
        }
        int streak = previous.quietStreak() + 1;
        if (streak >= 2) {
            return new Hold(raw.level(), raw.text(), 0);
        }
        return new Hold(previous.level(), previous.text(), streak);
    }

    private static boolean over(Long ageSeconds) {
        return ageSeconds != null && ageSeconds > AGE_WARN_SECONDS;
    }
}
