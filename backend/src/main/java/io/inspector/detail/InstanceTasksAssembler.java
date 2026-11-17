package io.inspector.detail;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessInstanceRecord;
import io.inspector.client.TaskPage;
import io.inspector.client.TaskRecord;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.InstanceTasks;
import io.inspector.dto.InstanceTasks.TaskDto;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tasks tab. Moved from {@link InstanceDetailService} (issue #475). */
final class InstanceTasksAssembler {

    private final InstanceDetailService host;

    InstanceTasksAssembler(InstanceDetailService host) {
        this.host = host;
    }

    /* ================= tasks — historic ∪ runtime ================= */

    /**
     * The instance's user tasks, completed AND open (SPEC §4). Historic-first: the
     * historic-task table carries open tasks as rows with a null endTime, so one leg
     * already covers the whole lifecycle. The runtime leg then enriches open rows with
     * live-only state (suspension) and adds any task the history level hides — same
     * union doctrine as {@link #currentActivities}.
     */
    public InstanceTasks tasks(String engineId, String instanceId) {
        EngineConfig engine = host.engineForRead(engineId);
        ProcessInstanceRecord historic = host.requireHistoric(engine, instanceId);

        Map<String, TaskRecord> runtimeById = new LinkedHashMap<>();
        if (historic.endTime() == null) {
            for (TaskRecord task : host.flowable
                    .listRuntimeTasks(engine, CallPriority.INTERACTIVE, instanceId, engine.maxPageSizeOrDefault())
                    .dataOrEmpty()) {
                if (task.id() != null) runtimeById.put(task.id(), task);
            }
        }

        long historicTotal = 0;
        Map<String, TaskDto> rows = new LinkedHashMap<>();
        try {
            TaskPage page = host.flowable.listHistoricTaskInstances(
                    engine, CallPriority.INTERACTIVE, instanceId, engine.maxPageSizeOrDefault());
            historicTotal = page.total();
            for (TaskRecord task : page.dataOrEmpty()) {
                String id = task.id();
                if (id == null) continue;
                rows.put(id, InstanceDetailService.taskRow(task, runtimeById.get(id)));
            }
        } catch (Exception ex) {
            // Task history below audit level (legal engine config) — the runtime leg
            // still renders the open tasks; degrade, never 500.
            InstanceDetailService.log.debug("Historic-task leg unavailable on {}: {}", engine.id(), ex.toString());
        }
        for (Map.Entry<String, TaskRecord> entry : runtimeById.entrySet()) {
            rows.computeIfAbsent(entry.getKey(), id -> InstanceDetailService.taskRow(null, entry.getValue()));
        }

        List<TaskDto> tasks = rows.values().stream()
                .sorted(Comparator.comparing(TaskDto::createTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        long total = Math.max(historicTotal, tasks.size());
        return new InstanceTasks(tasks, total, total > tasks.size());
    }
}
