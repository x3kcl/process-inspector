package io.inspector.dto;

import io.inspector.dto.SearchRequest.InstanceStatus;

/**
 * The corrected status model (SPECIFICATION §3, ARCHITECTURE §2.3): Flowable has no FAILED
 * instance state, so the BFF derives status as FLAGS — statuses genuinely collide (a
 * suspended instance keeps its dead-letter jobs).
 *
 * <ul>
 *   <li>{@code ended} — historic instance {@code endTime != null}</li>
 *   <li>{@code suspended} — runtime instance {@code suspended} field (per-row enrichment)</li>
 *   <li>{@code hasDeadLetterJobs} — dead-letter scan membership (bounded exhaustive paging)</li>
 *   <li>{@code hasFailingJobs} — RETRYING tier: {@code jobs}/{@code timer-jobs} with
 *       {@code withException=true} (a failing async job parks in the timer table between
 *       attempts)</li>
 *   <li>{@code failedInSubprocess} — a dead-letter job in a call-activity CHILD, resolved up
 *       the {@code superProcessInstanceId} chain to this root</li>
 * </ul>
 */
public record InstanceStatusFlags(
        boolean ended,
        boolean suspended,
        boolean hasDeadLetterJobs,
        boolean hasFailingJobs,
        boolean failedInSubprocess) {

    public static final InstanceStatusFlags NONE = new InstanceStatusFlags(false, false, false, false, false);

    /**
     * The primary display chip, by the normative precedence COMPLETED / FAILED / RETRYING /
     * SUSPENDED / ACTIVE (SPECIFICATION §3). Collisions keep their secondary flags — the UI
     * renders those as badges next to the chip.
     */
    public InstanceStatus primaryStatus() {
        if (ended) return InstanceStatus.COMPLETED;
        if (hasDeadLetterJobs || failedInSubprocess) return InstanceStatus.FAILED;
        if (hasFailingJobs) return InstanceStatus.RETRYING;
        if (suspended) return InstanceStatus.SUSPENDED;
        return InstanceStatus.ACTIVE;
    }
}
