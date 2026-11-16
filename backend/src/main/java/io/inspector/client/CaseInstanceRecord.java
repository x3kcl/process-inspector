package io.inspector.client;

/** One CMMN case instance as the runtime or the historic endpoints serialise it (#474). */
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
