package io.inspector.registry;

import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Buckets a failed registry probe's exception into one of a small, closed set of UI-safe classes
 * (issue #275). The probe controller's own doctrine (topology oracle risk) forbids surfacing the
 * raw exception text to the UI — the full text stays audit-only (response_snippet, issue #223/
 * #231). This classifier draws the line at exception TYPE, never MESSAGE TEXT, so nothing
 * hostname/IP/port-shaped ever reaches the wire: only which of a handful of known-safe buckets
 * the failure falls into.
 */
public final class ProbeFailureClassifier {

    /** The base-URL re-validation at the dial point rejected it before anything was dialled. */
    public static final String SSRF_REJECTED = "ssrf_rejected";

    /** {@code GuardedCaller#resolveSecret} — the configured secret env var isn't set on the BFF. */
    public static final String MISSING_SECRET_REF = "missing_secret_ref";

    /** The engine responded 401/403 — it was reached, but rejected the configured credentials. */
    public static final String AUTH_REJECTED = "auth_rejected";

    /** The engine was reached and replied, but not with a usable engine-info payload (a stale or
     *  wildly version-mismatched engine most often looks like this — 404/500/unparseable body). */
    public static final String UNEXPECTED_RESPONSE = "unexpected_response";

    /** The safe default: connection refused/timeout/DNS failure/circuit open — never dialled OR no
     *  reply came back at all. */
    public static final String UNREACHABLE = "unreachable";

    private ProbeFailureClassifier() {}

    /** The exact prefix {@code GuardedCaller#resolveSecret} throws on an unset secret ref. */
    private static final String MISSING_SECRET_PREFIX = "Secret env var not set:";

    public static String classify(RuntimeException e) {
        if (e instanceof IllegalStateException
                && e.getMessage() != null
                && e.getMessage().startsWith(MISSING_SECRET_PREFIX)) {
            return MISSING_SECRET_REF;
        }
        if (e instanceof HttpClientErrorException.Unauthorized || e instanceof HttpClientErrorException.Forbidden) {
            return AUTH_REJECTED;
        }
        if (e instanceof RestClientResponseException) {
            // Reachable — the engine answered, just not usefully (wrong app at that URL, a much
            // older/newer engine returning an unexpected shape, a 5xx, etc).
            return UNEXPECTED_RESPONSE;
        }
        // ResourceAccessException (connect refused/timeout/DNS), CallNotPermittedException (circuit
        // open), and anything else unclassified all land here — the pre-#275 behavior, kept as the
        // conservative fallback.
        return UNREACHABLE;
    }
}
