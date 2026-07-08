package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One CMMN dead-letter job (Case Inspector Phase 1, R-SEM-20) — a failed async job the BPMN
 * status join deliberately EXCLUDES because it belongs to a co-deployed CMMN engine sharing
 * flowable-rest's job tables (SPEC §3 hygiene). Phase 0 only COUNTED these as
 * {@code outOfScopeDeadletters}; Phase 1 makes them drillable rows.
 *
 * <p>Sourced from the CMMN Management REST API's {@code GET …/cmmn-api/cmmn-management/
 * deadletter-jobs}, the SIBLING of the process-api {@code /service} context — see
 * {@code FlowableEngineClient.listCmmnDeadLetterJobs}. The discriminator is a NON-NULL
 * {@code caseInstanceId} (proven live: that same list also projects BPMN jobs, which carry a
 * null case attribution — Q1 of the CMMN wire-shape spike, docs/CMMN-SCOPE-PHASE-0.md §1.1).
 *
 * <p>Unlike a BPMN {@code processDefinitionId} ({@code key:version:uuid}), a CMMN
 * {@code caseDefinitionId} is a bare uuid — the case key/name are NOT derivable from it, so
 * they are left to a later (bounded) enrichment; the drill value here is {@code elementName}
 * + {@code exceptionMessage} + {@code caseInstanceId}. Read-only in Phase 1 (no move/discard —
 * CMMN corrective actions are Phase 3, under the full corrective-actions rails). Gated 6.8+;
 * nullable fields differ by engine.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CmmnDeadLetterJob(
        String id,
        String caseInstanceId, // the discriminator — non-null iff this row is CMMN-scoped
        String caseDefinitionId, // bare uuid (NOT key:version:uuid); key/name not derivable from it
        String planItemInstanceId, // the failed plan item's instance
        String elementId, // the failing case element (e.g. a service task)
        String elementName,
        Integer retries, // 0 on a dead-letter row
        String exceptionMessage, // first-line snippet of the failure
        String createTime,
        String dueDate,
        String tenantId) {}
