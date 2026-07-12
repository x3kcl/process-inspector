package io.inspector.security;

import io.inspector.audit.AuditService;
import io.inspector.audit.AuditUnavailableException;
import io.inspector.security.mapping.SecurityAlertChannel;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * Break-glass login success (IDP-SECURITY.md §7). Loud + audited + capped:
 *
 * <ul>
 *   <li><b>Alert on login</b> (R-OPS-03) — always fires the security-alert channel.</li>
 *   <li><b>Audited</b> fail-closed to Postgres; on a DB outage it degrades to the tamper-evident
 *       file sink (the concurrent-outage escape). If NEITHER is writable the login is refused —
 *       nothing break-glass ever runs unaudited.</li>
 *   <li><b>4 h session cap</b> stamped on the session (< the normal 24 h) — read by
 *       {@link AbsoluteSessionTimeoutFilter}.</li>
 * </ul>
 */
public class BreakGlassSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(BreakGlassSuccessHandler.class);

    private final AuditService audit;
    private final BreakGlassAuditSink sink;
    private final SecurityAlertChannel alert;
    private final BreakGlassThrottle throttle;
    private final Duration sessionCap;
    private final java.time.Clock clock;

    public BreakGlassSuccessHandler(
            AuditService audit,
            BreakGlassAuditSink sink,
            SecurityAlertChannel alert,
            BreakGlassThrottle throttle,
            int sessionCapHours,
            java.time.Clock clock) {
        this.audit = audit;
        this.sink = sink;
        this.alert = alert;
        this.throttle = throttle;
        this.sessionCap = Duration.ofHours(sessionCapHours);
        this.clock = clock;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        String actor = authentication.getName();
        // A correct password clears the brute-force counter (S4) so an operator who fat-fingered the
        // sealed password before getting it right is never held in a residual cooldown.
        throttle.reset(actor);
        alert.fire("break-glass-login", "sealed-account login by " + actor);

        Map<String, Object> payload = Map.of("event", "break-glass-login", "actor", actor);
        boolean audited;
        try {
            audit.recordConfigEvent("break-glass-login", actor, true, payload);
            audited = true;
        } catch (AuditUnavailableException e) {
            // The deliberate fail-closed exception: Postgres is down (maybe concurrent with the IdP
            // outage). Degrade to the tamper-evident file sink; write-success gates the login.
            audited = sink.append(actor, "break-glass-login", payload);
        }
        if (!audited) {
            log.error(
                    "BREAK_GLASS_UNAUDITABLE actor={} — neither Postgres nor the file sink is writable; refusing",
                    actor);
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "break-glass audit unavailable");
            return;
        }

        var session = request.getSession();
        session.setAttribute(AbsoluteSessionTimeoutFilter.SESSION_CAP_MS_ATTR, sessionCap.toMillis());
        // Anchor the cap at LOGIN, not at any pre-login (CSRF-priming) session's creation — so the 4 h
        // is measured from when break-glass actually opened (Copilot S5b review).
        session.setAttribute(
                AbsoluteSessionTimeoutFilter.CREATED_AT, clock.instant().toEpochMilli());
        log.warn(
                "BREAK_GLASS_SESSION_OPEN actor={} cap={} — permanent red banner + reason-on-every-verb",
                actor,
                sessionCap);
        response.sendRedirect(request.getContextPath() + "/");
    }
}
