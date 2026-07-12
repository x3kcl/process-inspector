package io.inspector.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: the break-glass brute-force throttle (S4). The load-bearing property is that it is a
 * SELF-HEALING progressive delay — a legitimate operator is never permanently locked out — while an
 * attacker is slowed to a doubling, capped crawl.
 */
class BreakGlassThrottleTest {

    private static final String USER = "break-glass";

    /** A hand-advanced clock so the delay/cooldown is exercised without real time (no sleeps). */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-07-12T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Test
    void backoffScheduleDoublesFromTheThirdFailureAndCaps() {
        assertThat(BreakGlassThrottle.requiredDelaySeconds(1)).isZero();
        assertThat(BreakGlassThrottle.requiredDelaySeconds(2)).isZero();
        assertThat(BreakGlassThrottle.requiredDelaySeconds(3)).isEqualTo(1);
        assertThat(BreakGlassThrottle.requiredDelaySeconds(4)).isEqualTo(2);
        assertThat(BreakGlassThrottle.requiredDelaySeconds(5)).isEqualTo(4);
        assertThat(BreakGlassThrottle.requiredDelaySeconds(6)).isEqualTo(8);
        assertThat(BreakGlassThrottle.requiredDelaySeconds(7)).isEqualTo(16);
        assertThat(BreakGlassThrottle.requiredDelaySeconds(8)).isEqualTo(BreakGlassThrottle.MAX_DELAY_SECONDS);
        assertThat(BreakGlassThrottle.requiredDelaySeconds(100)).isEqualTo(BreakGlassThrottle.MAX_DELAY_SECONDS);
    }

    @Test
    void firstTwoFailuresAreFreeThenTheThirdImposesADelayThatElapses() {
        MutableClock clock = new MutableClock();
        BreakGlassThrottle throttle = new BreakGlassThrottle(clock);

        throttle.recordFailure(USER);
        throttle.recordFailure(USER);
        assertThat(throttle.retryAfter(USER)).isZero(); // honest typos cost nothing

        throttle.recordFailure(USER); // 3rd → 1s cooldown
        assertThat(throttle.retryAfter(USER)).isEqualTo(Duration.ofSeconds(1));

        clock.advance(Duration.ofMillis(1001)); // the door reopens on its own
        assertThat(throttle.retryAfter(USER)).isZero();
    }

    @Test
    void aCorrectPasswordResetsTheCounterSoTheOperatorIsNeverHeldBack() {
        MutableClock clock = new MutableClock();
        BreakGlassThrottle throttle = new BreakGlassThrottle(clock);
        for (int i = 0; i < 6; i++) {
            throttle.recordFailure(USER);
        }
        assertThat(throttle.retryAfter(USER)).isPositive();

        throttle.reset(USER); // success

        assertThat(throttle.retryAfter(USER)).isZero();
        assertThat(throttle.recordFailure(USER)).isEqualTo(1); // count restarted from scratch
    }
}
