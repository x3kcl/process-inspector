package io.inspector.security;

import io.inspector.security.mapping.SecurityAlertChannel;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * Break-glass login FAILURE handling (S4): counts the attempt into the {@link BreakGlassThrottle},
 * logs it, and alerts on a sustained burst (the detective backstop — a failed break-glass attempt
 * previously alerted NOBODY). The response is {@code 429 + Retry-After} once the account has entered
 * its progressive-delay cooldown, else a generic {@code 401} — the same generic text either way so a
 * probe learns nothing about the account beyond "denied".
 */
public class BreakGlassFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(BreakGlassFailureHandler.class);

    /** Alert on the Nth consecutive failure, then every further N (5, 10, 15…) — attack signal without fatigue. */
    static final int ALERT_EVERY = 5;

    private final BreakGlassThrottle throttle;
    private final SecurityAlertChannel alert;
    private final String username;

    public BreakGlassFailureHandler(BreakGlassThrottle throttle, SecurityAlertChannel alert, String username) {
        this.throttle = throttle;
        this.alert = alert;
        this.username = username;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException, ServletException {
        String ip = request.getRemoteAddr();
        int count = throttle.recordFailure(username);
        log.warn("BREAK_GLASS_LOGIN_FAILED user={} consecutiveFailures={} ip={}", username, count, ip);
        if (count >= ALERT_EVERY && count % ALERT_EVERY == 0) {
            alert.fire(
                    "break-glass-brute-force",
                    count + " consecutive failed break-glass sign-in attempts (most recent source " + ip + ")");
        }

        Duration wait = throttle.retryAfter(username);
        if (!wait.isZero()) {
            long seconds = Math.max(1, wait.toSeconds());
            response.setHeader("Retry-After", Long.toString(seconds));
            response.sendError(429, "too many break-glass attempts — retry after " + seconds + "s");
        } else {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "break-glass sign-in failed");
        }
    }
}
