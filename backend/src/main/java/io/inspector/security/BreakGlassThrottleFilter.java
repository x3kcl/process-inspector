package io.inspector.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The pre-auth gate for the {@link BreakGlassThrottle} (S4): sits before the form-login filter and
 * refuses a {@code POST /break-glass} with {@code 429 + Retry-After} while the sealed account is in a
 * progressive-delay cooldown. A clean login (no prior failures) passes straight through, so the
 * emergency door is never bricked — only a burst of failures is slowed.
 */
public class BreakGlassThrottleFilter extends OncePerRequestFilter {

    private final BreakGlassThrottle throttle;
    private final String username;
    private final RequestMatcher matcher = new AntPathRequestMatcher("/break-glass", "POST");

    public BreakGlassThrottleFilter(BreakGlassThrottle throttle, String username) {
        this.throttle = throttle;
        this.username = username;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (matcher.matches(request)) {
            Duration wait = throttle.retryAfter(username);
            if (!wait.isZero()) {
                long seconds = Math.max(1, wait.toSeconds());
                response.setHeader("Retry-After", Long.toString(seconds));
                response.sendError(429, "too many break-glass attempts — retry after " + seconds + "s");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
