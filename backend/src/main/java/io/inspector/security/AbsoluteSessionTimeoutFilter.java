package io.inspector.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Absolute session cap (IDP-SECURITY.md §5, R-SAFE-07): a session is killed a fixed duration
 * after it was created, regardless of activity — the idle cap
 * ({@code server.servlet.session.timeout}) bounds inactivity, this bounds total lifetime so a
 * kept-warm session can't outlive the deprovisioning window. On expiry the session is
 * invalidated and the security context cleared, then the request continues unauthenticated so
 * the chain's own entry point answers uniformly (401 for {@code /api}, the login redirect for a
 * browser navigation) — this filter never writes a bespoke response.
 *
 * <p>Clock-driven (not {@code System}) so the cap is testable with a fake {@link Clock} +
 * Awaitility, never {@code Thread.sleep} (ArchUnit-enforced).
 */
public class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AbsoluteSessionTimeoutFilter.class);

    /** Epoch-milli stamp of session creation, set on first sight of a session. */
    static final String CREATED_AT = "inspector.session.createdAtEpochMs";

    private final Duration cap;
    private final Clock clock;

    public AbsoluteSessionTimeoutFilter(Duration cap, Clock clock) {
        this.cap = cap;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Instant now = clock.instant();
            Object stamp = session.getAttribute(CREATED_AT);
            if (stamp instanceof Long createdAtMs) {
                if (Duration.between(Instant.ofEpochMilli(createdAtMs), now).compareTo(cap) > 0) {
                    log.info("session {} exceeded the absolute cap ({}) — invalidating", session.getId(), cap);
                    session.invalidate();
                    SecurityContextHolder.clearContext();
                    // Fall through unauthenticated: the chain's entry point answers 401 / redirect.
                    chain.doFilter(request, response);
                    return;
                }
            } else {
                // First request that sees this session — stamp its birth. Auth mechanisms create
                // the session before this filter runs, so the stamp anchors at session creation.
                session.setAttribute(CREATED_AT, now.toEpochMilli());
            }
        }
        chain.doFilter(request, response);
    }
}
