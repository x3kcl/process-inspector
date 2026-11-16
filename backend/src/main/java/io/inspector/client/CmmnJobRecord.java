package io.inspector.client;

/** One row of a {@code /cmmn-management/*jobs} lane (#474). */
public record CmmnJobRecord(
        String id,
        String caseInstanceId,
        String caseDefinitionId,
        String planItemInstanceId,
        String elementId,
        String elementName,
        Integer retries,
        String exceptionMessage,
        String createTime,
        String dueDate,
        String tenantId) {}
