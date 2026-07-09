package io.inspector.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Transport / header / session posture (IDP-SECURITY.md §8, R-OPS-16). Kept in its own record
 * under {@code inspector.security.http} so the dev/basic {@link SecurityProperties} call sites
 * are untouched. Everything is config-bounded so nothing bricks: CSP ships <b>report-only</b>
 * first (a flag flips to enforce per deploy once tuned against the real bpmn-js/AG-Grid/CodeMirror
 * bundle), and HSTS is <b>opt-in, off by default</b> so the app never double-emits
 * {@code Strict-Transport-Security} alongside the proxy's deliberately-weak {@code stsSeconds}.
 */
@ConfigurationProperties(prefix = "inspector.security.http")
public record HttpHardeningProperties(
        /* Content-Security-Policy directives; a bpmn-js/AG-Grid/CodeMirror-safe default is used if blank. */
        String csp,
        /* CSP as report-only (Content-Security-Policy-Report-Only) vs enforced. Default report-only. */
        Boolean cspReportOnly,
        /* Permissions-Policy header value; a deny-all-sensors default is used if blank. */
        String permissionsPolicy,
        /* HSTS opt-in (the proxy owns HSTS by default — never double-emit). Default off. */
        boolean hstsEnabled,
        /* HSTS max-age seconds when enabled. */
        Long hstsMaxAgeS,
        /* Absolute session cap in hours (R-SAFE-07): a session is killed this long after creation
         * regardless of activity. Idle cap is server.servlet.session.timeout. Default 24h. */
        Integer sessionAbsoluteCapHours) {

    /**
     * A CSP that renders bpmn-js + AG-Grid + CodeMirror + inline SVG. Deliberately permissive
     * ({@code 'unsafe-inline'}/{@code 'unsafe-eval'}) for the <b>report-only-first</b> phase: the
     * point is to observe real violations against the live bundle, then TIGHTEN and flip to enforce
     * per deploy (the S6 tune-then-enforce follow-up). It must never touch {@code .bjs-powered-by}
     * (R-GOV-05).
     */
    public static final String DEFAULT_CSP = "default-src 'self'; "
            + "script-src 'self' 'unsafe-inline' 'unsafe-eval'; "
            + "style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; font-src 'self' data:; connect-src 'self'; "
            + "worker-src 'self' blob:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

    public static final String DEFAULT_PERMISSIONS_POLICY =
            "geolocation=(), camera=(), microphone=(), usb=(), payment=(), magnetometer=(), gyroscope=()";

    public String cspOrDefault() {
        return csp != null && !csp.isBlank() ? csp : DEFAULT_CSP;
    }

    public boolean cspReportOnlyOrDefault() {
        return cspReportOnly == null || cspReportOnly; // report-only unless explicitly disabled
    }

    public String permissionsPolicyOrDefault() {
        return permissionsPolicy != null && !permissionsPolicy.isBlank()
                ? permissionsPolicy
                : DEFAULT_PERMISSIONS_POLICY;
    }

    public long hstsMaxAgeSOrDefault() {
        return hstsMaxAgeS != null ? hstsMaxAgeS : 31536000L; // 1y, only emitted when hstsEnabled
    }

    public int sessionAbsoluteCapHoursOrDefault() {
        return sessionAbsoluteCapHours != null && sessionAbsoluteCapHours > 0 ? sessionAbsoluteCapHours : 24;
    }
}
