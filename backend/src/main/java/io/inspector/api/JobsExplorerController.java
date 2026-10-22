package io.inspector.api;

import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessApiClient.JobLaneKind;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cross-engine job browse (#515). One page per engine, then a local age filter. The total is a
 * lower bound when an engine's page is shorter than its reported total.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobsExplorerController {

    private final EngineRegistry registry;
    private final ProcessApiClient flowable;

    public JobsExplorerController(EngineRegistry registry, ProcessApiClient flowable) {
        this.registry = registry;
        this.flowable = flowable;
    }

    @GetMapping
    public JobsPage list(
            @RequestParam(defaultValue = "executable") String lane,
            @RequestParam(required = false) Integer olderThanMinutes,
            @RequestParam(defaultValue = "20") int size) {
        JobLaneKind kind = laneOf(lane);
        int pageSize = Math.min(Math.max(size, 1), 50);
        Instant cutoff = olderThanMinutes == null ? null : Instant.now().minus(olderThanMinutes, ChronoUnit.MINUTES);
        List<JobRow> rows = new ArrayList<>();
        List<String> capped = new ArrayList<>();
        for (EngineConfig engine : registry.all()) {
            try {
                FlowablePage page = flowable.listJobs(engine, CallPriority.INTERACTIVE, kind, Map.of(), 0, pageSize);
                List<Map<String, Object>> data = page == null ? List.of() : page.dataOrEmpty();
                if (page != null && page.total() > data.size()) {
                    capped.add(engine.id());
                }
                for (Map<String, Object> job : data) {
                    if (cutoff != null && !olderThan(job, cutoff)) {
                        continue;
                    }
                    rows.add(new JobRow(
                            engine.id(),
                            str(job, "id"),
                            str(job, "processInstanceId"),
                            str(job, "elementId"),
                            str(job, "dueDate"),
                            str(job, "createTime"),
                            str(job, "exceptionMessage"),
                            str(job, "lockOwner")));
                }
            } catch (RuntimeException e) {
                capped.add(engine.id());
            }
        }
        return new JobsPage(kind.name(), rows, capped);
    }

    private static JobLaneKind laneOf(String lane) {
        return switch (lane.toLowerCase(java.util.Locale.ROOT)) {
            case "timer" -> JobLaneKind.TIMER;
            case "suspended" -> JobLaneKind.SUSPENDED;
            case "deadletter" -> JobLaneKind.DEADLETTER;
            case "history" -> JobLaneKind.HISTORY;
            default -> JobLaneKind.EXECUTABLE;
        };
    }

    private static boolean olderThan(Map<String, Object> job, Instant cutoff) {
        String stamp = str(job, "createTime");
        if (stamp == null) {
            return false;
        }
        try {
            return Instant.parse(stamp).isBefore(cutoff);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String str(Map<String, Object> job, String key) {
        Object value = job.get(key);
        return value == null ? null : value.toString();
    }

    public record JobsPage(String lane, List<JobRow> rows, List<String> cappedEngines) {}

    public record JobRow(
            String engineId,
            String jobId,
            String processInstanceId,
            String elementId,
            String dueDate,
            String createTime,
            String exceptionMessage,
            String lockOwner) {}
}
