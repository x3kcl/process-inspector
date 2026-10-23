package io.inspector.action;

import io.inspector.security.Role;
import java.util.Optional;

/**
 * The M4 single-target verb catalog (SPEC §5 table) with each verb's guard tier (SPEC §6)
 * and role floor (R-SAFE-01 / ARCH §5). Tier-2 flow surgery (change-state, rerun-from,
 * restart) is v1.1; reassign-task / unassign-task landed in v1.x #6; bulk is M5.
 *
 * Role floors follow the ladder: tier 0 + unstick = RESPONDER, tiers 1–2 = OPERATOR,
 * tier 3 = ADMIN. unstick-event is guard-TIER 1 (reason discipline) but role-floor
 * RESPONDER — the L1/L2 runbook tier explicitly includes it (SPEC §2).
 */
public enum ActionVerb {
    RETRY_JOB("retry-job", 0, Role.RESPONDER, TargetKind.INSTANCE),
    TRIGGER_TIMER("trigger-timer", 0, Role.RESPONDER, TargetKind.INSTANCE),
    RESCHEDULE_TIMER("reschedule-timer", 1, Role.OPERATOR, TargetKind.INSTANCE),
    MOVE_TO_HISTORY_JOB("move-to-history-job", 0, Role.RESPONDER, TargetKind.INSTANCE),
    SUSPEND("suspend", 0, Role.RESPONDER, TargetKind.INSTANCE),
    ACTIVATE("activate", 0, Role.RESPONDER, TargetKind.INSTANCE),
    UNSTICK_EVENT("unstick-event", 1, Role.RESPONDER, TargetKind.INSTANCE),
    EDIT_VARIABLE("edit-variable", 1, Role.OPERATOR, TargetKind.INSTANCE),
    COMPLETE_TASK("complete-task", 1, Role.OPERATOR, TargetKind.INSTANCE),
    REASSIGN_TASK("reassign-task", 1, Role.OPERATOR, TargetKind.INSTANCE),
    UNASSIGN_TASK("unassign-task", 1, Role.OPERATOR, TargetKind.INSTANCE),
    CLAIM_TASK("claim-task", 1, Role.OPERATOR, TargetKind.INSTANCE),
    DELEGATE_TASK("delegate-task", 1, Role.OPERATOR, TargetKind.INSTANCE),
    RESOLVE_TASK("resolve-task", 1, Role.OPERATOR, TargetKind.INSTANCE),
    UPDATE_TASK("update-task", 1, Role.OPERATOR, TargetKind.INSTANCE),
    ADD_IDENTITY_LINK("add-identity-link", 1, Role.OPERATOR, TargetKind.INSTANCE),
    REMOVE_IDENTITY_LINK("remove-identity-link", 1, Role.OPERATOR, TargetKind.INSTANCE),
    ADD_PARTICIPANT("add-participant", 1, Role.OPERATOR, TargetKind.INSTANCE),
    REMOVE_PARTICIPANT("remove-participant", 1, Role.OPERATOR, TargetKind.INSTANCE),
    DELETE_TASK("delete-task", 3, Role.ADMIN, TargetKind.INSTANCE),
    TERMINATE_DELETE("terminate-delete", 3, Role.ADMIN, TargetKind.INSTANCE),
    DELETE_DEADLETTER("delete-deadletter", 3, Role.ADMIN, TargetKind.INSTANCE),
    DELETE_JOB("delete-job", 3, Role.ADMIN, TargetKind.INSTANCE),
    DELETE_HISTORY("delete-history", 3, Role.ADMIN, TargetKind.INSTANCE),
    DELETE_CASE_HISTORY("delete-case-history", 3, Role.ADMIN, TargetKind.INSTANCE),
    PLAN_ITEM_ACTION("plan-item-action", 1, Role.OPERATOR, TargetKind.INSTANCE),
    CHANGE_PLAN_ITEM("change-plan-item", 1, Role.OPERATOR, TargetKind.INSTANCE),
    TERMINATE_CASE("terminate-case", 3, Role.ADMIN, TargetKind.INSTANCE),
    DELETE_CASE("delete-case", 3, Role.ADMIN, TargetKind.INSTANCE),
    MIGRATE_CASE("migrate-case", 3, Role.ADMIN, TargetKind.INSTANCE),
    EDIT_CASE_VARIABLE("edit-case-variable", 1, Role.OPERATOR, TargetKind.INSTANCE),
    SUSPEND_DEFINITION("suspend-definition", 3, Role.ADMIN, TargetKind.DEFINITION),
    ACTIVATE_DEFINITION("activate-definition", 3, Role.ADMIN, TargetKind.DEFINITION);

    /** What the composite path segment addresses. */
    public enum TargetKind {
        INSTANCE,
        DEFINITION
    }

    private final String path;
    private final int tier;
    private final Role minRole;
    private final TargetKind targetKind;

    ActionVerb(String path, int tier, Role minRole, TargetKind targetKind) {
        this.path = path;
        this.tier = tier;
        this.minRole = minRole;
        this.targetKind = targetKind;
    }

    public String path() {
        return path;
    }

    public int tier() {
        return tier;
    }

    public Role minRole() {
        return minRole;
    }

    public TargetKind targetKind() {
        return targetKind;
    }

    /** Resolves the URL path segment; empty for unknown verbs (controller answers 404). */
    public static Optional<ActionVerb> fromPath(String path) {
        for (ActionVerb verb : values()) {
            if (verb.path.equals(path)) {
                return Optional.of(verb);
            }
        }
        return Optional.empty();
    }
}
