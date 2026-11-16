package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One CMMN case instance as {@code /cmmn-runtime/case-instances} or
 * {@code /cmmn-history/historic-case-instances} serialises it — the typed wire shape of the CMMN
 * case-instance reads (#474). {@code caseDefinitionId} is a bare uuid (no {@code key:version}).
 * The runtime row carries {@code parentId}; the historic row carries {@code endTime} and
 * {@code superProcessInstanceId}; both carry {@code state}. Neither 6.8 nor 7.1 serialises
 * {@code caseDefinitionKey} — it is read when an engine sends it. A field the leg does not
 * serialise is null. Times stay the engine's verbatim strings.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CaseInstanceRecord(
        String id,
        String name,
        String businessKey,
        String caseDefinitionId,
        String caseDefinitionKey,
        String caseDefinitionName,
        String startTime,
        String endTime,
        String startUserId,
        String state,
        String parentId,
        String superProcessInstanceId,
        String tenantId) {}
