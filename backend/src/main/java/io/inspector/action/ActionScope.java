package io.inspector.action;

/**
 * Which engine scope a corrective action targets (Case Inspector Phase 3). The verb rails —
 * RBAC, reason discipline, protected-instance guard, fail-closed audit, no-auto-retry, the
 * server-computed cURL — are entirely scope-neutral; only two seams differ by scope: the
 * server-fresh target restatement (which DLQ projection the job is read from) and the one
 * engine call (which {@code /management} vs {@code /cmmn-management} path the move hits). The
 * {@code audit_entry} instance-id column is generic, so a case id records identically to a
 * process-instance id.
 *
 * <p>{@link #BPMN} is the default for the process-instance and definition routes. {@link #CMMN}
 * is used only by the case-scoped route and is capability-gated ({@code scopeType}, Flowable
 * ≥ 6.8) — older engines are dead-letter-blind on the cmmn context.
 */
public enum ActionScope {
    BPMN,
    CMMN
}
