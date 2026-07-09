package io.inspector.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

/**
 * TS-SESSION-01 (R-SAFE-07): the absolute session cap kills a session a fixed duration after
 * creation regardless of activity. Driven by a mutable fake {@link Clock} (never
 * {@code Thread.sleep} — ArchUnit-enforced): the cap is proven by advancing the clock past it.
 */
class AbsoluteSessionTimeoutFilterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-07-09T00:00:00Z"));
    private final AbsoluteSessionTimeoutFilter filter = new AbsoluteSessionTimeoutFilter(Duration.ofHours(24), clock);

    private int invokeWith(MockHttpSession session) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (session != null) {
            request.setSession(session);
        }
        AtomicInteger downstream = new AtomicInteger();
        FilterChain chain = (req, res) -> downstream.incrementAndGet();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return downstream.get();
    }

    @Test
    void firstSightOfASessionStampsItsBirthAndPassesThrough() throws Exception {
        MockHttpSession session = new MockHttpSession();
        assertThat(invokeWith(session)).isEqualTo(1);
        assertThat(session.getAttribute(AbsoluteSessionTimeoutFilter.CREATED_AT))
                .isEqualTo(clock.instant().toEpochMilli());
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void aSessionWithinTheCapSurvives() throws Exception {
        MockHttpSession session = new MockHttpSession();
        invokeWith(session); // stamp
        clock.advance(Duration.ofHours(23).plusMinutes(59));
        assertThat(invokeWith(session)).isEqualTo(1);
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void aSessionPastTheCapIsInvalidatedButTheChainStillRuns() throws Exception {
        MockHttpSession session = new MockHttpSession();
        invokeWith(session); // stamp at T0
        clock.advance(Duration.ofHours(24).plusSeconds(1));
        // The request continues (chain runs) so the entry point answers 401/redirect uniformly,
        // but the session is gone — the next request is unauthenticated.
        assertThat(invokeWith(session)).isEqualTo(1);
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void noSessionIsANoOp() throws Exception {
        assertThat(invokeWith(null)).isEqualTo(1);
    }

    /** A hand-cranked Clock — advance() moves time forward without sleeping. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
