package io.inspector.security.reauth;

import java.time.Duration;
import java.time.Instant;

/**
 * The bounded-freshness decision for the dangerous set (IDP-SECURITY.md §5, R-SAFE-07). A tier-3
 * verb, a bulk operation, or a mapping write re-authenticates only when the session's authentication
 * ({@code auth_time}) is older than the freshness window — within-window verbs run on a token
 * ≤N-min stale, so a P1 fan-out of 20 retries is not 20 MFA prompts (⚠️ DevOps). An <b>absent</b>
 * {@code auth_time} fails closed (re-auth demanded) — never a silent pass. Pure so the boundary is a
 * rung-1 gate with a fake clock (no {@code Thread.sleep}).
 */
public final class SessionFreshness {

    private SessionFreshness() {}

    /**
     * @param authTime the session principal's {@code auth_time} (null = absent → fail closed)
     * @param now      the current instant (injected clock)
     * @param window   the freshness window (N ≤ 15 min)
     * @return true when the dangerous verb must force a re-authentication
     */
    public static boolean requiresReauth(Instant authTime, Instant now, Duration window) {
        if (authTime == null) {
            return true; // absent auth_time → fail-closed, demand re-auth
        }
        return Duration.between(authTime, now).compareTo(window) > 0;
    }
}
