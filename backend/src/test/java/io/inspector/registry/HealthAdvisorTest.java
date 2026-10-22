package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HealthAdvisorTest {

    @Test
    void aWorseSignalShowsImmediately() {
        HealthAdvisor.Signal ok = new HealthAdvisor.Signal(HealthAdvisor.Level.OK, "calm");
        HealthAdvisor.Hold held = HealthAdvisor.step(new HealthAdvisor.Hold(ok.level(), ok.text(), 0), warn());

        assertThat(held.level()).isEqualTo(HealthAdvisor.Level.WARN);
        assertThat(held.quietStreak()).isZero();
    }

    @Test
    void oneQuietSampleDoesNotClearAWarning() {
        HealthAdvisor.Hold warning = new HealthAdvisor.Hold(HealthAdvisor.Level.WARN, "behind", 0);

        HealthAdvisor.Hold held = HealthAdvisor.step(warning, ok());

        assertThat(held.level()).isEqualTo(HealthAdvisor.Level.WARN);
        assertThat(held.quietStreak()).isEqualTo(1);
    }

    @Test
    void twoQuietSamplesClearAWarning() {
        HealthAdvisor.Hold warning = new HealthAdvisor.Hold(HealthAdvisor.Level.WARN, "behind", 1);

        HealthAdvisor.Hold held = HealthAdvisor.step(warning, ok());

        assertThat(held.level()).isEqualTo(HealthAdvisor.Level.OK);
        assertThat(held.text()).isEqualTo("calm");
    }

    @Test
    void unreachableIsCriticalAndHistoryAgeWarns() {
        assertThat(HealthAdvisor.raw(EngineHealth.unreachable("down", 1), null, null)
                        .level())
                .isEqualTo(HealthAdvisor.Level.CRIT);

        EngineHealth staleHistory = new EngineHealth(
                true,
                "6.8.0",
                null,
                1,
                null,
                new EngineHealth.JobLanes(0, 0, 0, 0, 3L),
                null,
                0L,
                301L,
                null,
                null,
                null,
                null,
                null);
        HealthAdvisor.Signal signal = HealthAdvisor.raw(staleHistory, null, null);
        assertThat(signal.level()).isEqualTo(HealthAdvisor.Level.WARN);
        assertThat(signal.text()).contains("History backlog");
    }

    @Test
    void aStartDropVersusTheReferenceWarns() {
        EngineHealth calm =
                new EngineHealth(true, "6.8.0", null, 1, null, new EngineHealth.JobLanes(0, 0, 0, 0), null, 0L);

        assertThat(HealthAdvisor.raw(calm, 1L, 10L).text()).contains("reference window");
        assertThat(HealthAdvisor.raw(calm, 8L, 10L).level()).isEqualTo(HealthAdvisor.Level.OK);
    }

    private static HealthAdvisor.Signal warn() {
        return new HealthAdvisor.Signal(HealthAdvisor.Level.WARN, "behind");
    }

    private static HealthAdvisor.Signal ok() {
        return new HealthAdvisor.Signal(HealthAdvisor.Level.OK, "calm");
    }
}
