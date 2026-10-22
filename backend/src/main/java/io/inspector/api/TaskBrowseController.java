package io.inspector.api;

import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cross-engine open-task browse (#521). Additive to {@code GET /api/tasks?person=}. One page per
 * engine. The total is a lower bound when an engine's page is shorter than its reported total.
 */
@RestController
public class TaskBrowseController {

    private final EngineRegistry registry;
    private final ProcessApiClient flowable;

    public TaskBrowseController(EngineRegistry registry, ProcessApiClient flowable) {
        this.registry = registry;
        this.flowable = flowable;
    }

    @GetMapping("/api/tasks/browse")
    public TaskBrowsePage list(
            @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) String candidateGroup,
            @RequestParam(defaultValue = "false") boolean unassigned,
            @RequestParam(defaultValue = "false") boolean overdue,
            @RequestParam(required = false) String createdAfter,
            @RequestParam(required = false) String createdBefore,
            @RequestParam(defaultValue = "50") int size) {
        int pageSize = Math.min(Math.max(size, 1), 50);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("processDefinitionKey", processDefinitionKey);
        query.put("assignee", assignee);
        query.put("owner", owner);
        query.put("candidateGroup", candidateGroup);
        query.put("createdAfter", createdAfter);
        query.put("createdBefore", createdBefore);
        if (unassigned) {
            query.put("unassigned", "true");
        }
        if (overdue) {
            query.put("dueBefore", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        }
        List<TaskRow> rows = new ArrayList<>();
        List<String> capped = new ArrayList<>();
        for (EngineConfig engine : registry.all()) {
            try {
                FlowablePage page = flowable.listRuntimeTasks(engine, CallPriority.INTERACTIVE, query, pageSize);
                List<Map<String, Object>> data = page == null ? List.of() : page.dataOrEmpty();
                if (page != null && page.total() > data.size()) {
                    capped.add(engine.id());
                }
                for (Map<String, Object> task : data) {
                    rows.add(new TaskRow(
                            engine.id(),
                            str(task, "id"),
                            str(task, "name"),
                            str(task, "processInstanceId"),
                            str(task, "processDefinitionId"),
                            str(task, "assignee"),
                            str(task, "owner"),
                            str(task, "dueDate"),
                            str(task, "createTime")));
                }
            } catch (RuntimeException e) {
                capped.add(engine.id());
            }
        }
        return new TaskBrowsePage(rows, capped);
    }

    @GetMapping("/api/tasks/{engineId}/{taskId}/identitylinks")
    public List<IdentityLinkRow> identityLinks(@PathVariable String engineId, @PathVariable String taskId) {
        EngineConfig engine = registry.require(engineId);
        List<IdentityLinkRow> rows = new ArrayList<>();
        for (Map<String, Object> link : flowable.listIdentityLinks(engine, CallPriority.INTERACTIVE, taskId)) {
            String user = str(link, "user");
            String group = str(link, "group");
            rows.add(new IdentityLinkRow(
                    user != null ? "users" : "groups", user != null ? user : group, str(link, "type")));
        }
        return rows;
    }

    private static String str(Map<String, Object> task, String key) {
        Object value = task.get(key);
        return value == null ? null : value.toString();
    }

    public record TaskBrowsePage(List<TaskRow> rows, List<String> cappedEngines) {}

    public record TaskRow(
            String engineId,
            String taskId,
            String name,
            String processInstanceId,
            String processDefinitionId,
            String assignee,
            String owner,
            String dueDate,
            String createTime) {}

    public record IdentityLinkRow(String family, String identityId, String type) {}
}
