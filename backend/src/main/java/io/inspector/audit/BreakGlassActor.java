package io.inspector.audit;

/**
 * Per-thread marker: is the mutation currently being dispatched acting under a break-glass
 * (sealed-account) session (IDP-SECURITY.md §7, R-SAFE-11 / S7)? Set at each mutation dispatch on
 * the SAME thread that writes the audit row — including {@code BulkJobService}'s virtual-thread
 * workers, where a thread-local set on the controller thread would NOT inherit onto the worker.
 *
 * <p>This exists for the SAME reason as {@code ForwardedActor}: on a bulk worker the
 * {@code SecurityContextHolder} is EMPTY (the acting identity is threaded as an explicit
 * {@code Authentication} precisely because thread-locals don't inherit), so {@link AuditService}'s
 * security-context read would silently record {@code breakGlass=false} on every per-item audit row
 * of a job submitted under break-glass. {@code CorrectiveActionService} — the single-target path
 * AND the per-item bulk executor — sets this from the passed {@code Authentication} before the
 * audit row is written, and {@link AuditService} consults it as a fallback to the security context.
 * On the ordinary request thread it stays unset and the security-context read remains authoritative.
 *
 * <p>MUST be cleared in a {@code finally} so the flag never leaks onto a reused carrier thread
 * (defence in depth — the bulk executor is virtual-thread-per-task, so threads are not reused).
 */
public final class BreakGlassActor {

    private static final ThreadLocal<Boolean> HOLDER = new ThreadLocal<>();

    private BreakGlassActor() {}

    /** Mark (or unmark) the current thread as dispatching under a break-glass session. */
    public static void set(boolean breakGlass) {
        if (breakGlass) {
            HOLDER.set(Boolean.TRUE);
        } else {
            HOLDER.remove();
        }
    }

    /** True iff the current thread was marked break-glass for this dispatch. */
    public static boolean current() {
        return Boolean.TRUE.equals(HOLDER.get());
    }

    /** MUST run in a {@code finally} so the flag never leaks onto a reused carrier thread. */
    public static void clear() {
        HOLDER.remove();
    }
}
