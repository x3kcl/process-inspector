package io.inspector.dto;

import io.inspector.dto.SearchRequest.InstanceStatus;

/**
 * One normalized results-grid row. compositeId ({@code engineId:processInstanceId})
 * is the instance's identity everywhere outside a single-engine call.
 */
public record ProcessInstanceRow(
        String compositeId,
        String engineId,
        String engineName,
        String engineColor,
        String processInstanceId,
        String businessKey,
        String processDefinitionKey,
        String processDefinitionName,
        InstanceStatus status,
        String startTime,
        String endTime,
        String currentActivityOrError // dead-letter exception snippet when FAILED
        ) {}
