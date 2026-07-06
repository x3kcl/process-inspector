package io.inspector.registry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Rung 1: the ARCH §2.5 version cliffs are pure logic. */
class EngineCapabilitiesTest {

    @Test
    void preCliffSixThreeHasNoVersionCapabilities() {
        EngineCapabilities caps = EngineCapabilities.fromVersion("6.3.1", true);
        assertThat(caps.changeState()).isFalse();
        assertThat(caps.migration()).isFalse();
        assertThat(caps.externalWorkerJobs()).isFalse();
        assertThat(caps.scopeType()).isFalse();
        assertThat(caps.activityHistory()).isTrue(); // probed, not derived
    }

    @Test
    void sixFourAddsChangeStateOnly() {
        EngineCapabilities caps = EngineCapabilities.fromVersion("6.4.2", false);
        assertThat(caps.changeState()).isTrue();
        assertThat(caps.migration()).isFalse();
        assertThat(caps.externalWorkerJobs()).isFalse();
    }

    @Test
    void sixFiveAddsMigration() {
        EngineCapabilities caps = EngineCapabilities.fromVersion("6.5.0", false);
        assertThat(caps.changeState()).isTrue();
        assertThat(caps.migration()).isTrue();
        assertThat(caps.externalWorkerJobs()).isFalse();
    }

    @Test
    void sixEightPassesAllFourCliffs_evenWithFourPartVersion() {
        EngineCapabilities caps = EngineCapabilities.fromVersion("6.8.0.1", false);
        assertThat(caps.changeState()).isTrue();
        assertThat(caps.migration()).isTrue();
        assertThat(caps.externalWorkerJobs()).isTrue();
        assertThat(caps.scopeType()).isTrue();
    }

    @Test
    void sevenXPassesAllCliffs() {
        EngineCapabilities caps = EngineCapabilities.fromVersion("7.1.0", true);
        assertThat(caps).isEqualTo(new EngineCapabilities(true, true, true, true, true));
    }

    @Test
    void unparseableVersionsYieldNoVersionCapabilities() {
        for (String garbage : new String[] {null, "", "  ", "unknown", "v6.8", "six.eight"}) {
            EngineCapabilities caps = EngineCapabilities.fromVersion(garbage, false);
            assertThat(caps).as("version=%s", garbage)
                    .isEqualTo(EngineCapabilities.none());
        }
    }

    @Test
    void parseMajorMinorHandlesShortAndLongForms() {
        assertThat(EngineCapabilities.parseMajorMinor("7")).containsExactly(7, 0);
        assertThat(EngineCapabilities.parseMajorMinor("6.8.0.1")).containsExactly(6, 8);
        assertThat(EngineCapabilities.parseMajorMinor("6.x")).isNull();
    }
}
