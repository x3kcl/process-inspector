package io.inspector.client;

/**
 * Per-thread holder for the acting human forwarded to identity-forwarding engines as
 * {@code X-Forwarded-User} (M4-CLOSEOUT §2). Set at each mutation dispatch on the SAME thread that
 * makes the blocking write call — including {@code BulkJobService}'s virtual-thread workers, where
 * a thread-local set on the controller thread would NOT inherit onto the worker (D2a). The write
 * client's request interceptor reads {@link #current()} during the outbound call.
 *
 * <p>This is deliberately NOT a {@code SecurityContextHolder} read: that context is empty on every
 * bulk worker (the actor is threaded as an explicit {@code Authentication} precisely because
 * thread-locals don't inherit) and would silently drop the header on bulk, or forward a stale
 * human. Callers set the value from the SAME audit-row actor the row was written with
 * ({@code AuditEntry.forwardedIdentity()}), so the invariant "forwarded == audit attribution"
 * holds structurally.
 *
 * <p>The stored value is already sanitized (control chars stripped, trimmed, length-capped,
 * blank → null) so the interceptor is a pure null-check.
 */
public final class ForwardedActor {

    /** Header-injection guard + a sane cap for a username / subject. */
    static final int MAX_LENGTH = 256;

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private ForwardedActor() {}

    /** Set the forwarded actor for the current thread (sanitized); a blank/null actor clears it. */
    public static void set(String actor) {
        String clean = sanitize(actor);
        if (clean == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(clean);
        }
    }

    /** The sanitized forwarded actor for the current thread, or {@code null} if none. */
    public static String current() {
        return HOLDER.get();
    }

    /** MUST run in a {@code finally} so the value never leaks onto a reused carrier thread. */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * Strip control characters (CR/LF header-injection defense + anything else &lt; 0x20), trim,
     * cap length, and collapse blank → {@code null}. A header value can never smuggle a newline
     * into the outbound request.
     */
    static String sanitize(String actor) {
        if (actor == null) {
            return null;
        }
        String stripped = actor.replaceAll("\\p{Cntrl}", "").trim();
        if (stripped.isEmpty()) {
            return null;
        }
        return stripped.length() > MAX_LENGTH ? stripped.substring(0, MAX_LENGTH) : stripped;
    }
}
