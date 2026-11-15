package io.inspector.client;

import java.util.List;
import java.util.Map;

/** One process instance as the runtime or the historic endpoints serialise it (#474). */
public record ProcessInstanceRecord(
        String id,
        String name,
        String businessKey,
        String processDefinitionId,
        String processDefinitionName,
        String startTime,
        String startUserId,
        String tenantId,
        String superProcessInstanceId,
        Boolean suspended,
        String endTime,
        Long durationInMillis,
        String deleteReason,
        String startActivityId,
        String endActivityId,
        List<Map<String, Object>> variables) {}
