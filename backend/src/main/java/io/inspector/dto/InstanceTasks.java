package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * GET /api/instances/{engineId}/{id}/tasks — the instance's user tasks, completed AND
 * open, in one ledger (SPEC §4). Historic-first (the historic table carries open tasks as
 * rows with a null endTime) unioned with the runtime rows, which add live-only state
 * (suspension) and cover engines whose task history is dialed down. {@code total} is the
 * engine-reported historic total, so a page-capped list stays honest via {@code truncated}.
 */
public record InstanceTasks(List<TaskDto> tasks, long total, boolean truncated) {

    /** One user task. State is derived, never trusted from a single leg (ARCH §2.3 spirit). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskDto(
            String id,
            String name,
            String taskDefinitionKey, // the BPMN activity id — the diagram-sync key
            String assignee,
            String owner,
            String createTime,
            String endTime, // null while the task is open
            String dueDate,
            Long durationMs, // engine-reported for completed tasks
            String state) { // ACTIVE | SUSPENDED | COMPLETED

        public static final String STATE_ACTIVE = "ACTIVE";
        public static final String STATE_SUSPENDED = "SUSPENDED";
        public static final String STATE_COMPLETED = "COMPLETED";
    }
}
