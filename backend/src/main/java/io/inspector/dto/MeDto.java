package io.inspector.dto;

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
        Map<String, String> engineRoles) {}
