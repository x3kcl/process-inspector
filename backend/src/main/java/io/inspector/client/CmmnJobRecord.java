package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One row of a {@code /cmmn-management} job lane (executable or dead-letter), list or by-id — the
 * typed wire shape of the CMMN job reads (#474). Its own record, not {@link JobRecord}: the row is
 * attributed by {@code caseInstanceId} / {@code caseDefinitionId} (bare uuid) /
 * {@code planItemInstanceId}, carries no process attribution and no {@code scopeType}, and its
 * {@code elementId} is the plan-item DEFINITION id. A job without case attribution (a BPMN
 * projection, a 7.x case-migration-status job) has those fields null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
