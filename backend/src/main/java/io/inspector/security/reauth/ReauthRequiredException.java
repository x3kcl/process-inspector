package io.inspector.security.reauth;

/**
 * The dangerous verb needs a fresher authentication than the current session carries (IDP-SECURITY.md
 * §5, R-SAFE-07). Thrown as a pre-condition — BEFORE the audit gate and BEFORE the operator has typed
 * the confirm token + reason — so the SPA can run its full-page re-auth interstitial and replay the
 * verb on a server-fresh principal. Mapped by {@code ActionExceptionHandler} to a 401 carrying the
 * {@code reauth-required} marker (never a plain "session expired" 401, so the SPA can distinguish a
 * freshness challenge from a full sign-out).
 */
public class ReauthRequiredException extends RuntimeException {

    private final int freshnessWindowSeconds;

    public ReauthRequiredException(int freshnessWindowSeconds) {
        super("Re-authentication required: this action needs a sign-in newer than " + (freshnessWindowSeconds / 60)
                + " minutes. Re-authenticate and try again — nothing happened.");
        this.freshnessWindowSeconds = freshnessWindowSeconds;
    }

    /** The bounded freshness window (seconds) the SPA must re-authenticate within. */
    public int freshnessWindowSeconds() {
        return freshnessWindowSeconds;
    }
}
