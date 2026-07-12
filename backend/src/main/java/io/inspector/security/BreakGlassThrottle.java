package io.inspector.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Brute-force protection for the sealed {@code /break-glass} account (S4). Break-glass is the
 * LAST-RESORT door during an IdP outage, so the design is a self-healing PROGRESSIVE DELAY, never a
 * hard lockout: an attacker is slowed to a crawl, but a legitimate operator who fat-fingers the
 * shared password during a P1 is NEVER permanently locked out — a correct password succeeds
 * immediately ({@link #reset}) and the counter also self-expires after a quiet window.
 *
 * <p>Keyed by the single sealed username (there is exactly one break-glass account), so the filter
 * needs no request-body parsing. The first two failures are free; from the third, the NEXT attempt
 * is refused with {@code 429 + Retry-After} until a doubling delay (1s, 2s, 4s, … capped 30s)
 * elapses. The delay throttles the SUBSEQUENT attempt — the servlet thread never sleeps.
 */
@Component
public class BreakGlassThrottle {

    /** Failures 1–2 carry no delay (an operator's honest typo shouldn't cost a P1 minute). */
    static final int FREE_ATTEMPTS = 2;
    /** The doubling delay never exceeds this — a determined attacker is slowed, not the operator. */
    static final int MAX_DELAY_SECONDS = 30;
    /** No failure for this long ⇒ the counter is forgotten (self-heal). */
    static final Duration IDLE_RESET = Duration.ofMinutes(15);

    private final Cache<String, Attempt> attempts =
            Caffeine.newBuilder().expireAfterWrite(IDLE_RESET).maximumSize(64).build();
    private final Clock clock;

    public BreakGlassThrottle(Clock clock) {
        this.clock = clock;
    }

    private record Attempt(int count, long lastFailEpochMs) {}

    /**
     * How long the caller must wait before the next {@code /break-glass} attempt is allowed, or
     * {@link Duration#ZERO} when it may proceed now.
     */
    public Duration retryAfter(String username) {
        Attempt a = attempts.getIfPresent(username);
        if (a == null) {
            return Duration.ZERO;
        }
        long requiredMs = requiredDelaySeconds(a.count()) * 1000L;
        long remaining = requiredMs - (clock.millis() - a.lastFailEpochMs());
        return remaining > 0 ? Duration.ofMillis(remaining) : Duration.ZERO;
    }

    /** Record one failed attempt; returns the new consecutive-failure count. */
    public int recordFailure(String username) {
        Attempt updated = attempts.asMap()
                .compute(username, (k, prev) -> new Attempt(prev == null ? 1 : prev.count() + 1, clock.millis()));
        return updated.count();
    }

    /** Clear the counter — called on a SUCCESSFUL login so a legit operator is never held back. */
    public void reset(String username) {
        attempts.invalidate(username);
    }

    /**
     * The required inter-attempt delay after {@code count} consecutive failures: 0 for the first two,
     * then 1s, 2s, 4s, 8s … doubling, capped at {@link #MAX_DELAY_SECONDS}.
     */
    static long requiredDelaySeconds(int count) {
        if (count <= FREE_ATTEMPTS) {
            return 0;
        }
        int exponent = Math.min(count - FREE_ATTEMPTS - 1, 20); // guard against shift overflow
        long delay = 1L << exponent;
        return Math.min(delay, MAX_DELAY_SECONDS);
    }
}
