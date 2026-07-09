package io.inspector.security.mapping;

import io.inspector.security.Role;

/**
 * A single intended change to the group→scope mapping (IDP-SECURITY.md §6). Modelled as an
 * add/remove of one grant — an edit is a remove+add pair — so the whole CRUD surface is four
 * kinds. Ladder fields ({@code role}/{@code engineId}/{@code tenantId}) are set for
 * {@code LADDER_*}; {@code fleetGrant} for {@code FLEET_*}. Serialized into the proposal store's
 * {@code change_json} when a change needs four-eyes.
 */
public record GrantChange(Kind kind, String group, Role role, String engineId, String tenantId, FleetGrant fleetGrant) {

    public enum Kind {
        LADDER_ADD,
        LADDER_REMOVE,
        FLEET_ADD,
        FLEET_REMOVE
    }

    public static GrantChange ladderAdd(String group, Role role, String engineId, String tenantId) {
        return new GrantChange(Kind.LADDER_ADD, group, role, engineId, tenantId, null);
    }

    public static GrantChange ladderRemove(String group, Role role, String engineId, String tenantId) {
        return new GrantChange(Kind.LADDER_REMOVE, group, role, engineId, tenantId, null);
    }

    public static GrantChange fleetAdd(String group, FleetGrant fleetGrant) {
        return new GrantChange(Kind.FLEET_ADD, group, null, null, null, fleetGrant);
    }

    public static GrantChange fleetRemove(String group, FleetGrant fleetGrant) {
        return new GrantChange(Kind.FLEET_REMOVE, group, null, null, null, fleetGrant);
    }

    public boolean isLadder() {
        return kind == Kind.LADDER_ADD || kind == Kind.LADDER_REMOVE;
    }

    public boolean isAdd() {
        return kind == Kind.LADDER_ADD || kind == Kind.FLEET_ADD;
    }

    /** Human-legible one-liner for the audit summary + the R-SAFE-08 proposal inbox. */
    public String summary() {
        String verb = isAdd() ? "grant" : "revoke";
        if (isLadder()) {
            return verb + " " + role + " on " + engineId + "/" + tenantId + " to group '" + group + "'";
        }
        return verb + " fleet " + fleetGrant + " to group '" + group + "'";
    }
}
