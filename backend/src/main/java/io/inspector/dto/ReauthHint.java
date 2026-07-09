package io.inspector.dto;

import java.time.Instant;

/**
 * The dangerous-set freshness hint the SPA reads at verb intent (modal open) so it runs the re-auth
 * interstitial BEFORE the operator types the confirm token + reason — never after (IDP-SECURITY.md
 * §5). Purely a presentation hint: the BFF's {@code DangerousActionReauthGate} stays the gate on
 * every dangerous request, so what the UI pre-empts and what the BFF refuses can never drift.
 *
 * @param required   is the session too stale to run a dangerous verb right now (computed at the
 *                   instant {@code /api/me} is answered)?
 * @param freshUntil the instant the session stops being dangerous-verb-fresh — {@code null} for a
 *                   session that is exempt (dev/basic, break-glass) or has no {@code auth_time} at
 *                   all (an OIDC session that fails closed); the SPA treats null-with-required-false
 *                   as "never needs re-auth" and null-with-required-true as "re-auth now".
 * @param windowSeconds the bounded freshness window (≤ 15 min) — copy for the interstitial's "your
 *                   sign-in is older than N minutes" message.
 */
public record ReauthHint(boolean required, Instant freshUntil, int windowSeconds) {}
