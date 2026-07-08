package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The plan-item state-machine timeline of one CMMN case (Case Inspector Phase 2) — the CMMN
 * analog of the BPMN activity timeline.
 *
 * <p><b>Runtime-only on 6.8</b> (spike Q6): {@code cmmn-history/historic-plan-item-instances}
 * 404s, so an ENDED case has no plan-item source at all. Rather than a fabricated empty list
 * (which reads as "no plan items"), such a case returns {@code available=false} with an
 * {@code unavailableReason} — the vitals header still renders from case history. {@code truncated}
 * flags a bounded scan that hit its cap (iron rule — never an unpaged fetch).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CasePlanItems(
        boolean available, String unavailableReason, boolean truncated, List<CasePlanItem> planItems) {

    /**
     * One plan item with its lifecycle timestamps (the state-machine progression: available →
     * enabled/active → completed/terminated) and a live job annotation. {@code elementId} is the
     * CMMN DI shape key (matches {@link CaseDiagram}'s marker sets); {@code stageInstanceId}
     * nests it under a parent stage. {@code liveJobState} is joined by {@code id} ==
     * {@code planItemInstanceId} on the case's jobs (spike Q7); null on a healthy plan item.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CasePlanItem(
            String id,
            String elementId,
            String name,
            String planItemDefinitionType,
            String state,
            boolean stage,
            String stageInstanceId,
            String createTime,
            String lastAvailableTime,
            String lastEnabledTime,
            String lastStartedTime,
            String completedTime,
            String occurredTime,
            String terminatedTime,
            String exitTime,
            String endedTime,
            CmmnLiveJobState liveJobState) {}
}
