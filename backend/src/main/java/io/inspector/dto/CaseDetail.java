package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * CMMN case vitals (Case Inspector Phase 2) — the polymorphic sibling of the BPMN
 * {@code InstanceDetail}, the header of the {@code /case/{engineId}/{caseInstanceId}} detail page.
 * Historic-first (a completed case renders, never 404s): read from
 * {@code cmmn-history/historic-case-instances/{id}} and overlaid with the runtime case for live
 * state. Read-only, gated 6.8+ (spike Q4/Q6).
 *
 * <p>{@code state} is normalized to ACTIVE / COMPLETED / TERMINATED — there is NO SUSPENDED (a
 * case cannot suspend, spike Q2). The bare-uuid {@code caseDefinitionId} is resolved to a
 * readable {@code caseDefinitionKey}/{@code caseDefinitionName}/{@code caseDefinitionVersion}
 * (null when the definition is undeployed). {@code superProcessInstanceId} links a case back to
 * a BPMN parent process (cross-engine); Phase 2 surfaces the id, it does not walk the hierarchy.
 * {@code failing} is present only when the case has a dead-lettered plan item.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaseDetail(
        String engineId,
        String caseInstanceId,
        String businessKey,
        String state, // ACTIVE | COMPLETED | TERMINATED — never SUSPENDED
        String caseDefinitionId, // bare uuid
        String caseDefinitionKey,
        String caseDefinitionName,
        Integer caseDefinitionVersion,
        String startTime,
        String endTime,
        String startUserId,
        String superProcessInstanceId, // a BPMN parent, when this case was called from a process
        String parentId, // a parent CASE (a case started by another case)
        String tenantId,
        boolean present,
        boolean ended,
        CaseFailing failing) {

    /**
     * The "why stuck" summary of a case carrying a dead-lettered plan item — the count, the first
     * exception line, the failing element's readable name, and the (bounded) list of dead-letter
     * jobs so the UI can offer a per-job retry (Case Inspector Phase 3). Present only when
     * {@code deadLetterJobCount > 0}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CaseFailing(
            int deadLetterJobCount, String firstException, String failingElementName, List<DeadLetterJobRef> jobs) {

        /**
         * The minimum a Phase-3 retry needs: the job {@code id} (the move target) plus enough
         * context to name the row honestly. The full case-scoped enumeration lives in
         * {@link CmmnDeadLetterJob}; this is the per-case slice already fetched for the count.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record DeadLetterJobRef(String id, String elementName, String exceptionMessage, Integer retries) {}
    }
}
