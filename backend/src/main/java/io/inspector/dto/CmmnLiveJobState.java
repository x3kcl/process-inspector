package io.inspector.dto;

/**
 * The live job annotation on a CMMN plan item (Case Inspector Phase 2), the CMMN analog of the
 * BPMN timeline's {@code liveJobState}: {@code FAILED} = a dead-letter job is parked on the plan
 * item (retries exhausted), {@code RETRYING} = a failing job with retries remaining. Joined to a
 * plan item by {@code planItemInstanceId} == the plan item's {@code id} (spike Q7 — NOT by
 * {@code elementId}, which on a job row is the plan-item DEFINITION id). Absent (null) on a
 * healthy plan item.
 */
public enum CmmnLiveJobState {
    FAILED,
    RETRYING
}
