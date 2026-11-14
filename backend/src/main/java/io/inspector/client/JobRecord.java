package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One row of a {@code /management/*-jobs} lane (executable, timer, suspended, dead-letter,
 * history) — the typed wire shape of the jobs endpoint family (#474).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobRecord(
        String id,
        String processInstanceId,
        String processDefinitionId,
        String executionId,
        String elementId,
        String elementName,
        String exceptionMessage,
        Integer retries,
        String createTime,
        String dueDate,
        String tenantId,
        String scopeType,
        String jobHandlerType,
        String handlerType,
        String lockOwner,
        String lockExpirationTime) {}
