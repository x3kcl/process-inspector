package io.inspector.dto;

import java.time.Instant;
import java.util.Map;

/**
 * The signed-in identity for the SPA's greyed-never-hidden affordances (SPEC §6). Roles
 * are resolved per engine through the SAME {@code RbacAuthorizer} path the mutation
 * guards use (scoped grants, hot-reloaded OIDC mapping) — so what the UI greys and what
 * the BFF refuses can never drift. Purely a presentation hint: the BFF check stays the
 * gate on every request.
 */
public record MeDto(
        String username,
        /** Highest ladder role held on ANY engine — the UI's coarse default. */
        String role,
        /** Highest ladder role per engine id — drives per-engine action greying. */
        Map<String, String> engineRoles,
        /** Fleet REGISTRY_ADMIN grant (v2 Registry CRUD) — greys the /admin/engines nav. */
        boolean registryAdmin,
        /** Apex ACCESS_ADMIN grant (v2 IdP-Security) — greys the /admin/access nav. */
        boolean accessAdmin,
        /** Break-glass session (v2 IdP-Security) — shows the permanent red banner + reason-on-every-verb. */
        boolean breakGlass,
        /**
         * Dangerous-set freshness hint (v2 IdP-Security §5) — drives the re-auth interstitial at
         * modal open so a stale OIDC session re-authenticates BEFORE the operator types the confirm
         * token. {@code required=false, freshUntil=null} for an exempt dev/basic or break-glass session.
         */
        ReauthHint reauth,
        /**
         * Absolute-cap guillotine instant (v2 IdP-Security §5, R-SAFE-07): when this session dies
         * regardless of activity (per-session break-glass 4 h override honoured). Drives the SPA's
         * warn-before-guillotine countdown banner. Null when the call rode no session. Presentation
         * only — {@code AbsoluteSessionTimeoutFilter} stays the enforcement.
         */
        Instant sessionExpiresAt) {}
