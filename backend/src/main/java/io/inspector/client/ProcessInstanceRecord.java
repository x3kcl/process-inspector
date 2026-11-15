package io.inspector.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * One process instance as {@code /runtime/process-instances}, {@code /query/process-instances},
 * {@code /history/historic-process-instances} or {@code /query/historic-process-instances}
 * serialises it — the typed wire shape of the process-instance endpoint family (#474). The runtime
 * row carries {@code suspended}; the historic row carries {@code endTime}, {@code durationInMillis},
 * {@code deleteReason} and the start/end activity ids. A field the leg (or the engine version) does
 * not serialise is null. Times stay the engine's verbatim strings; {@code variables} keeps the
 * engine's dynamic value maps (present only when the query asked for them).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProcessInstanceRecord(
        String id,
        String name,
        String businessKey,
        String processDefinitionId,
        String processDefinitionKey,
        String processDefinitionName,
        String startTime,
        String startUserId,
        String tenantId,
        String superProcessInstanceId,
        Boolean suspended,
        String endTime,
        Long durationInMillis,
        String deleteReason,
        String state,
        String startActivityId,
        String endActivityId,
        List<Map<String, Object>> variables) {}
