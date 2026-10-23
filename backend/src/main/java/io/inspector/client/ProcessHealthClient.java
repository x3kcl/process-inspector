package io.inspector.client;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient.JobLaneKind;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.HttpClientErrorException;

/** Health-strip size=1 totals. Moved from ProcessApiClient (issue #475). */
final class ProcessHealthClient {

    private final GuardedCaller guarded;

    ProcessHealthClient(GuardedCaller guarded) {
        this.guarded = guarded;
    }

    /* ---------- M1 health-strip calls: size=1 totals, never row fetches ---------- */

    /** Lane count via the size=1 total trick — no rows transferred. */
    public long countJobs(EngineConfig engine, CallPriority priority, JobLaneKind lane) {
        FlowablePage page = guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> uri.path(lane.path).queryParam("size", 1).build())
                        .retrieve()
                        .body(FlowablePage.class));
        return page != null ? page.total() : 0;
    }

    /**
     * Same count as {@link #countJobs}, or null when the route is missing. A missing history lane
     * must not become 0 (#507).
     */
    public Long countJobsIfSupported(EngineConfig engine, CallPriority priority, JobLaneKind lane) {
        try {
            return countJobs(engine, priority, lane);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /** Oldest history job (createTime asc), or null when the lane is empty or the route is missing. */
    public Map<String, Object> oldestHistoryJob(EngineConfig engine, CallPriority priority) {
        try {
            FlowablePage page = guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri(uri -> uri.path(JobLaneKind.HISTORY.path)
                                    .queryParam("size", 1)
                                    .queryParam("sort", "createTime")
                                    .queryParam("order", "asc")
                                    .build())
                            .retrieve()
                            .body(FlowablePage.class));
            List<Map<String, Object>> rows = page != null ? page.dataOrEmpty() : List.of();
            return rows.isEmpty() ? null : rows.get(0);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /**
     * Count-only historic process instances in a window. Null when the history route is missing.
     * {@code started} selects startedAfter/Before; otherwise finishedAfter/Before.
     */
    public Long countHistoricProcesses(
            EngineConfig engine,
            CallPriority priority,
            Instant after,
            Instant before,
            boolean started,
            boolean rootOnly) {
        return countHistoricProcesses(engine, priority, after, before, started, rootOnly, null);
    }

    public Long countHistoricProcesses(
            EngineConfig engine,
            CallPriority priority,
            Instant after,
            Instant before,
            boolean started,
            boolean rootOnly,
            String definitionKey) {
        String afterName = started ? "startedAfter" : "finishedAfter";
        String beforeName = started ? "startedBefore" : "finishedBefore";
        String afterIso =
                after.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        String beforeIso =
                before.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        try {
            FlowablePage page = guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri(uri -> {
                                var builder = uri.path("/history/historic-process-instances")
                                        .queryParam("size", 1)
                                        .queryParam(afterName, afterIso)
                                        .queryParam(beforeName, beforeIso);
                                if (rootOnly) {
                                    builder.queryParam("excludeSubprocesses", "true");
                                }
                                if (definitionKey != null && !definitionKey.isBlank()) {
                                    builder.queryParam("processDefinitionKey", definitionKey);
                                }
                                return builder.build();
                            })
                            .retrieve()
                            .body(FlowablePage.class));
            return page != null ? page.total() : 0L;
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /**
     * GET /runtime/process-instances — the cohort list. Blank filters are omitted.
     * {@code businessKey} and {@code businessKeyLike} are mutually exclusive; the caller picks one.
     * Measured 2026-10-22 on 6.8: exact key, like, startedAfter and startedBefore all narrow
     * {@code total}. The POST query's {@code businessKey} field did not.
     */
    public FlowablePage listRuntimeInstances(
            EngineConfig engine,
            CallPriority priority,
            String processDefinitionId,
            String businessKey,
            String businessKeyLike,
            String startedAfter,
            String startedBefore,
            int size) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> {
                            var builder = uri.path("/runtime/process-instances")
                                    .queryParam("processDefinitionId", processDefinitionId)
                                    .queryParam("size", size);
                            if (businessKey != null && !businessKey.isBlank()) {
                                builder.queryParam("businessKey", businessKey);
                            }
                            if (businessKeyLike != null && !businessKeyLike.isBlank()) {
                                builder.queryParam("businessKeyLike", businessKeyLike);
                            }
                            if (startedAfter != null && !startedAfter.isBlank()) {
                                builder.queryParam("startedAfter", startedAfter);
                            }
                            if (startedBefore != null && !startedBefore.isBlank()) {
                                builder.queryParam("startedBefore", startedBefore);
                            }
                            return builder.build();
                        })
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /** Finished historic process instances. Blank key and business key are omitted. */
    public FlowablePage listFinishedProcesses(
            EngineConfig engine,
            CallPriority priority,
            String definitionKey,
            String businessKey,
            String finishedBefore,
            int start,
            int size) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> {
                            var builder = uri.path("/history/historic-process-instances")
                                    .queryParam("finished", "true")
                                    .queryParam("finishedBefore", finishedBefore)
                                    .queryParam("size", size)
                                    .queryParam("start", start);
                            if (definitionKey != null && !definitionKey.isBlank()) {
                                builder.queryParam("processDefinitionKey", definitionKey);
                            }
                            if (businessKey != null && !businessKey.isBlank()) {
                                builder.queryParam("businessKey", businessKey);
                            }
                            return builder.build();
                        })
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /** DELETE /history/historic-process-instances/{id}?deleteReason= */
    public void deleteHistoricProcessInstance(
            EngineConfig engine, CallPriority priority, String processInstanceId, String deleteReason) {
        guarded.run(
                engine,
                priority,
                "delete-history",
                () -> guarded.writeClient(engine)
                        .delete()
                        .uri(uri -> uri.path("/history/historic-process-instances/{id}")
                                .queryParam("deleteReason", deleteReason)
                                .build(processInstanceId))
                        .retrieve()
                        .toBodilessEntity());
    }

    /** Historic tasks created or completed in the window. Null when the route is missing. */
    public Long countHistoricTasks(
            EngineConfig engine, CallPriority priority, Instant after, Instant before, boolean completed) {
        String afterName = completed ? "taskCompletedAfter" : "taskCreatedAfter";
        String beforeName = completed ? "taskCompletedBefore" : "taskCreatedBefore";
        String afterIso = after.truncatedTo(ChronoUnit.SECONDS).toString();
        String beforeIso = before.truncatedTo(ChronoUnit.SECONDS).toString();
        try {
            FlowablePage page = guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri(uri -> uri.path("/history/historic-task-instances")
                                    .queryParam("size", 1)
                                    .queryParam(afterName, afterIso)
                                    .queryParam(beforeName, beforeIso)
                                    .build())
                            .retrieve()
                            .body(FlowablePage.class));
            return page != null ? page.total() : 0L;
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /** Oldest executable job row (dueDate asc), or null when the lane is empty. */
    public Map<String, Object> oldestExecutableJob(EngineConfig engine, CallPriority priority) {
        FlowablePage page = guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> uri.path(JobLaneKind.EXECUTABLE.path)
                                .queryParam("size", 1)
                                .queryParam("sort", "dueDate")
                                .queryParam("order", "asc")
                                .build())
                        .retrieve()
                        .body(FlowablePage.class));
        List<Map<String, Object>> rows = page != null ? page.dataOrEmpty() : List.of();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Count of timers already past due at {@code dueBefore} — the overdue-timer alarm. */
    public long countOverdueTimers(EngineConfig engine, CallPriority priority, Instant dueBefore) {
        // Whole seconds only: Flowable's date parsing 400s on fractional seconds.
        String dueBeforeIso = dueBefore.truncatedTo(ChronoUnit.SECONDS).toString();
        FlowablePage page = guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> uri.path(JobLaneKind.TIMER.path)
                                .queryParam("size", 1)
                                .queryParam("dueBefore", dueBeforeIso)
                                .build())
                        .retrieve()
                        .body(FlowablePage.class));
        return page != null ? page.total() : 0;
    }

    /**
     * Capability probe: does the engine record activity history? 200 = yes; a 4xx
     * (endpoint missing / history disabled) = no. Client errors are the expected
     * negative answer here and never trip the breaker (ignore-exceptions config).
     */
    public boolean probeActivityHistory(EngineConfig engine, CallPriority priority) {
        try {
            guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri(uri -> uri.path("/history/historic-activity-instances")
                                    .queryParam("size", 1)
                                    .build())
                            .retrieve()
                            .body(FlowablePage.class));
            return true;
        } catch (HttpClientErrorException e) {
            return false;
        }
    }
}
