package io.inspector.security.mapping;

/**
 * The orthogonal fleet grants (IDP-SECURITY.md §9) — apex authorities that sit OUTSIDE the
 * VIEWER→ADMIN ladder and never confer or derive from a ladder rung:
 *
 * <ul>
 *   <li>{@code REGISTRY_ADMIN} — repoints the credential vault (engines, v2 Registry CRUD).</li>
 *   <li>{@code ACCESS_ADMIN} — the apex: defines who holds every grant, INCLUDING the two fleet
 *       grants themselves. Higher-privilege than any tier-3 verb and than {@code REGISTRY_ADMIN}.</li>
 * </ul>
 *
 * A fleet grant must never read as a {@code (role, engine, tenant)} ladder row — hence its own
 * table ({@code group_fleet_grant}) and its own enum, distinct from {@link io.inspector.security.Role}.
 */
public enum FleetGrant {
    REGISTRY_ADMIN,
    ACCESS_ADMIN
}
