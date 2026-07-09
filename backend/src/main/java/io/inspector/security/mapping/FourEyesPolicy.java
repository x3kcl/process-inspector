package io.inspector.security.mapping;

import io.inspector.security.Role;
import io.inspector.security.ScopeGrant;
import java.util.Set;

/**
 * The four-eyes trigger, computed on the intended change (IDP-SECURITY.md §3.4, R-SAFE-14). A
 * second independent approver is MANDATORY for:
 *
 * <ul>
 *   <li>(a) adding/raising a ladder grant to a group the EDITOR is in — a <b>self-widen</b>;</li>
 *   <li>(b) <b>any</b> ladder grant of role ≥ OPERATOR with {@code engineId='*'} OR
 *       {@code tenantId='*'} — wildcard <b>breadth</b>, regardless of editor membership (one click
 *       would otherwise hand OPERATOR+/global to a broad group);</li>
 *   <li>(c) creating <b>any</b> fleet grant;</li>
 *   <li>(d) <b>removing</b> a fleet grant — apex removal is a takeover, not a fail-safe narrowing.</li>
 * </ul>
 *
 * Narrowing/removing a <i>ladder</i> grant is single-actor. Pure so the escalation matrix is a
 * rung-1 CI gate; a widen that slips through single-actor would be a quiet self-grant = Sev1.
 */
public final class FourEyesPolicy {

    private FourEyesPolicy() {}

    public static boolean requiresFourEyes(GrantChange change, Set<String> editorGroups) {
        return switch (change.kind()) {
            case FLEET_ADD, FLEET_REMOVE -> true; // (c) create any fleet grant, (d) remove any fleet grant
            case LADDER_REMOVE -> false; // narrowing a ladder grant is single-actor
            case LADDER_ADD -> {
                boolean selfWiden = editorGroups.contains(change.group()); // (a)
                boolean breadth = change.role().atLeast(Role.OPERATOR) // (b)
                        && (ScopeGrant.ANY.equals(change.engineId()) || ScopeGrant.ANY.equals(change.tenantId()));
                yield selfWiden || breadth;
            }
        };
    }

    /** ACCESS_ADMIN-grant changes additionally always fire the security-alert channel (§9 detective). */
    public static boolean firesSecurityAlert(GrantChange change) {
        return change.fleetGrant() == FleetGrant.ACCESS_ADMIN;
    }
}
