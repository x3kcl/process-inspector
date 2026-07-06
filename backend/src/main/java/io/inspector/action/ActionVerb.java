package io.inspector.action;

import io.inspector.security.Role;
import java.util.Optional;

/**
 * The M4 single-target verb catalog (SPEC §5 table) with each verb's guard tier (SPEC §6)
 * and role floor (R-SAFE-01 / ARCH §5). Tier-2 flow surgery (change-state, rerun-from,
 * restart) is v1.1; reassign-task and retry-now are deferred out of M4; bulk is M5.
 *
 * Role floors follow the ladder: tier 0 + unstick = RESPONDER, tiers 1–2 = OPERATOR,
 * tier 3 = ADMIN. unstick-event is guard-TIER 1 (reason discipline) but role-floor
 * RESPONDER — the L1/L2 runbook tier explicitly includes it (SPEC §2).
 */
public enum ActionVerb {
    RETRY_JOB("retry-job", 0, Role.RESPONDER, TargetKind.INSTANCE),
    TRIGGER_TIMER("trigger-timer", 0, Role.RESPONDER, TargetKind.INSTANCE),
    SUSPEND("suspend", 0, Role.RESPONDER, TargetKind.INSTANCE),
    ACTIVATE("activate", 0, Role.RESPONDER, TargetKind.INSTANCE),
    UNSTICK_EVENT("unstick-event", 1, Role.RESPONDER, TargetKind.INSTANCE),
    EDIT_VARIABLE("edit-variable", 1, Role.OPERATOR, TargetKind.INSTANCE),
    COMPLETE_TASK("complete-task", 1, Role.OPERATOR, TargetKind.INSTANCE),
    TERMINATE_DELETE("terminate-delete", 3, Role.ADMIN, TargetKind.INSTANCE),
    DELETE_DEADLETTER("delete-deadletter", 3, Role.ADMIN, TargetKind.INSTANCE),
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
