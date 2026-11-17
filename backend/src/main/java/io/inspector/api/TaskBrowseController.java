package io.inspector.api;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.IdentityLinkRecord;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.TaskPage;
import io.inspector.client.TaskRecord;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.ReadScopeGate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cross-engine open-task browse (#521). Additive to {@code GET /api/tasks?person=}. One page per
 * engine. The total is a lower bound when an engine's page is shorter than its reported total.
 *
 * <p>#747 (R-SAFE-17): under read-scope enforcement the browse covers only the engines the caller
 * may read (narrowed silently), and the identity links of a task on any other engine are refused
 * with the method-security 403 every engine-scoped read answers, before any engine call (an
 * unknown engine alike — no existence oracle). Off: unchanged.
 */
@RestController
public class TaskBrowseController {

    private final EngineRegistry registry;
    private final ProcessApiClient flowable;
    private final ReadScopeGate readScope;

    public TaskBrowseController(EngineRegistry registry, ProcessApiClient flowable, ReadScopeGate readScope) {
        this.registry = registry;
        this.flowable = flowable;
        this.readScope = readScope;
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
            @RequestParam(defaultValue = "50") int size,
            Authentication auth) {
        Set<String> readable = readScope.readableEngineIds(auth); // null = enforcement off
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
            if (readable != null && !readable.contains(engine.id())) {
                continue;
            }
            try {
                TaskPage page = flowable.listRuntimeTasks(engine, CallPriority.INTERACTIVE, query, pageSize);
                List<TaskRecord> data = page == null ? List.of() : page.dataOrEmpty();
                if (page != null && page.total() > data.size()) {
                    capped.add(engine.id());
                }
                for (TaskRecord task : data) {
                    rows.add(new TaskRow(
                            engine.id(),
                            task.id(),
                            task.name(),
                            task.processInstanceId(),
                            task.processDefinitionId(),
                            task.assignee(),
                            task.owner(),
                            task.dueDate(),
                            task.createTime()));
                }
            } catch (RuntimeException e) {
                capped.add(engine.id());
            }
        }
        return new TaskBrowsePage(rows, capped);
    }

    @GetMapping("/api/tasks/{engineId}/{taskId}/identitylinks")
    public List<IdentityLinkRow> identityLinks(
            @PathVariable String engineId, @PathVariable String taskId, Authentication auth) {
        if (readScope.enforced() && !readScope.canRead(auth, engineId)) {
            throw new AccessDeniedException("Access Denied"); // the @PreAuthorize refusal, verbatim
        }
        EngineConfig engine = registry.require(engineId);
        List<IdentityLinkRow> rows = new ArrayList<>();
        for (IdentityLinkRecord link : flowable.listIdentityLinks(engine, CallPriority.INTERACTIVE, taskId)) {
            String user = link.user();
            rows.add(new IdentityLinkRow(
                    user != null ? "users" : "groups", user != null ? user : link.group(), link.type()));
        }
        return rows;
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
