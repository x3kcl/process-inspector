package io.inspector.client;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * The process-api facade (Engine-client split, #86 — F2/F9): process-instance/history queries,
 * job/DLQ management, tasks, variables, executions, process-definitions, and every corrective-action
 * mutation, all against the engine's {@code /service} base. Every method threads {@link CallPriority}
 * explicitly (uniform, second positional param after {@code engine}) — no priority-less convenience
 * overload, so a caller can never silently land on the interactive lane by omission the way the old
 * god-class let CMMN/external-worker callers do.
 *
 * <p>Reads go through {@link GuardedCaller#readClient}; mutations (the {@code void}-returning verbs)
 * go through {@link GuardedCaller#writeClient} so write-ms (R-NFR-07) budgets them separately and are
 * NEVER retried here or anywhere upstream — a timed-out mutation is reported UNKNOWN, not re-fired
 * (corrective-actions skill §4).
 */
@Component
public class ProcessApiClient {

    /** Runtime variable collections answer a BARE JSON array, not the paged envelope. */
    private static final org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>> VARIABLE_LIST =
            new org.springframework.core.ParameterizedTypeReference<>() {};

    private final GuardedCaller guarded;

    public ProcessApiClient(GuardedCaller guarded) {
        this.guarded = guarded;
    }

    /** The four job queues (flowable-rest skill §3) and their management paths. */
    public enum JobLaneKind {
        EXECUTABLE("/management/jobs"),
        TIMER("/management/timer-jobs"),
        SUSPENDED("/management/suspended-jobs"),
        DEADLETTER("/management/deadletter-jobs");

        final String path;

        JobLaneKind(String path) {
            this.path = path;
        }
    }

    /* ---------- queries ---------- */

    /** POST /query/historic-process-instances — the primary search query. */
    public FlowablePage queryHistoricProcessInstances(
            EngineConfig engine, CallPriority priority, Map<String, Object> body) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .post()
                        .uri("/query/historic-process-instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /** POST /query/process-instances — runtime query (used for the suspended-ID set). */
    public FlowablePage queryRuntimeProcessInstances(
            EngineConfig engine, CallPriority priority, Map<String, Object> body) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .post()
                        .uri("/query/process-instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /**
     * One page of a job lane (M2a scan legs). {@code filters} maps straight onto the
     * collection's query params — {@code withException}, {@code processInstanceId},
     * {@code processDefinitionId}, {@code tenantId}. No sort param: {@code createTime}
     * is not an accepted job sort property, and the join legs don't care about order.
     */
    public FlowablePage listJobs(
            EngineConfig engine,
            CallPriority priority,
            JobLaneKind lane,
            Map<String, String> filters,
            int start,
            int size) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> {
                            var b = uri.path(lane.path)
                                    .queryParam("start", start)
                                    .queryParam("size", size);
                            filters.forEach(b::queryParam);
                            return b.build();
                        })
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /**
     * GET /history/historic-process-instances/{id} — the hierarchy-walk resolver: the
     * historic row (unlike the runtime one) carries {@code superProcessInstanceId} on every
     * engine version, and exists for ended children too. Null when the id is unknown.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getHistoricProcessInstance(
            EngineConfig engine, CallPriority priority, String processInstanceId) {
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri("/history/historic-process-instances/{id}", processInstanceId)
                            .retrieve()
                            .body(Map.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /**
     * GET /runtime/process-instances/{id} — per-row suspended fallback for engines whose
     * runtime query ignores {@code processInstanceIds} (proven on 6.3.1: the unknown field
     * is silently dropped and the query returns UNFILTERED data). 404 = not running.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getRuntimeProcessInstance(
            EngineConfig engine, CallPriority priority, String processInstanceId) {
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri("/runtime/process-instances/{id}", processInstanceId)
                            .retrieve()
                            .body(Map.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /**
     * GET /history/historic-activity-instances?processInstanceId=&finished=false — the
     * current-activity leg (SPEC §8, M2b): the unfinished activity rows of ONE instance,
     * matched in the BFF against the requested id/name substring. Identical wire shape on
     * 6.3.1/6.8/7.1 (probed live). One page suffices — an instance holds a handful of
     * unfinished activities, not thousands.
     */
    public FlowablePage listUnfinishedActivities(
            EngineConfig engine, CallPriority priority, String processInstanceId, String tenantId, int size) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> {
                            var b = uri.path("/history/historic-activity-instances")
                                    .queryParam("processInstanceId", processInstanceId)
                                    .queryParam("finished", "false")
                                    .queryParam("size", size);
                            if (tenantId != null && !tenantId.isBlank()) b.queryParam("tenantId", tenantId);
                            return b.build();
                        })
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /**
     * GET /repository/process-definitions?key= — resolves a definition-key filter to the
     * concrete per-version definition ids for DLQ-scan pushdown (ARCH §2.3). {@code version}/
     * {@code start} are nullable/zero for the common "all versions from page 1" case — this is the
     * ONE canonical signature (Engine-client split, #86: collapses 5 telescoping overloads that used
     * to exist here).
     */
    public FlowablePage listProcessDefinitionsByKey(
            EngineConfig engine, CallPriority priority, String key, Integer version, int start, int size) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> {
                            var b = uri.path("/repository/process-definitions")
                                    .queryParam("key", key)
                                    .queryParam("size", size);
                            if (start > 0) b.queryParam("start", start);
                            if (version != null) b.queryParam("version", version);
                            return b.build();
                        })
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /**
     * Versions of a key sorted by version DESCENDING (newest first) — a STABLE order for the
     * migration on-ramp's paging (the default sort is name-ascending, which makes start/size
     * paging prone to missed/duplicated rows under concurrent deploys). {@code page.total()} is
     * the full version count for the key, so the caller can detect truncation.
     */
    public FlowablePage listProcessDefinitionVersionsDesc(
            EngineConfig engine, CallPriority priority, String key, int start, int size) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> {
                            var b = uri.path("/repository/process-definitions")
                                    .queryParam("key", key)
                                    .queryParam("sort", "version")
                                    .queryParam("order", "desc")
                                    .queryParam("size", size);
                            if (start > 0) b.queryParam("start", start);
                            return b.build();
                        })
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /**
     * The single LATEST-version definition for a key ({@code latest=true}). The plain
     * {@code size=1} query does NOT return the latest — {@code /repository/process-definitions}
     * defaults to name-ascending, so version disambiguation needs {@code latest=true}
     * explicitly (used by instance migration's default-target resolution). Empty page when the
     * key is undeployed.
     */
    public FlowablePage latestProcessDefinitionByKey(EngineConfig engine, CallPriority priority, String key) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> uri.path("/repository/process-definitions")
                                .queryParam("key", key)
                                .queryParam("latest", true)
                                .queryParam("size", 1)
                                .build())
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /**
     * The LATEST-version definition of EVERY key on the engine ({@code latest=true}) — a bounded
     * metadata list of the deployed definition keys, NOT instance rows. Used by the Stage-0 leak
     * views (R-BAU-02) to enumerate the keys it then counts per age window with count-only
     * queries. {@code page.total()} lets the caller detect a key set larger than {@code size}
     * (a lower bound). {@code latest=true} collapses the version history to one row per key.
     */
    public FlowablePage listLatestProcessDefinitions(EngineConfig engine, CallPriority priority, int size) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> uri.path("/repository/process-definitions")
                                .queryParam("latest", true)
                                .queryParam("size", size)
                                .build())
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /**
     * GET /management/{lane}/{jobId}/exception-stacktrace — plain-text stacktrace, used by
     * the triage aggregation to refine ONE representative job per error group into its
     * root-cause class (R-SEM-03 unwrap). Null when the job is gone (retried/deleted
     * between scan and fetch — an acceptable snapshot race, the group falls back to its
     * message-only signature).
     */
    public String jobExceptionStacktrace(EngineConfig engine, CallPriority priority, JobLaneKind lane, String jobId) {
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri(lane.path + "/{jobId}/exception-stacktrace", jobId)
                            .retrieve()
                            .body(String.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /* ---------- M3 detail/resolve reads (Stage 2, SPEC §4) ---------- */

    /**
     * GET /runtime/executions?processInstanceId= — the execution tree of ONE open instance
     * (execution-local variable segregation, SPEC §4). One page suffices: an instance holds
     * a handful of executions; the page cap keeps a pathological multi-instance loop bounded.
     */
    public FlowablePage listExecutions(EngineConfig engine, CallPriority priority, String processInstanceId, int size) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> uri.path("/runtime/executions")
                                .queryParam("processInstanceId", processInstanceId)
                                .queryParam("size", size)
                                .build())
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /**
     * GET /runtime/process-instances/{id}/variables — the typed process-scope rows of a
     * RUNNING instance. Bare array on the wire (not the paged envelope). Null = not running.
     */
    public List<Map<String, Object>> listInstanceVariables(
            EngineConfig engine, CallPriority priority, String processInstanceId) {
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri("/runtime/process-instances/{id}/variables", processInstanceId)
                            .retrieve()
                            .body(VARIABLE_LIST));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /**
     * GET /runtime/executions/{id}/variables?scope=local — variables declared ON one
     * execution node (multi-instance loop locals live here, SPEC §4). Bare array.
     */
    public List<Map<String, Object>> listExecutionLocalVariables(
            EngineConfig engine, CallPriority priority, String executionId) {
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri(uri -> uri.path("/runtime/executions/{id}/variables")
                                    .queryParam("scope", "local")
                                    .build(executionId))
                            .retrieve()
                            .body(VARIABLE_LIST));
        } catch (HttpClientErrorException.NotFound e) {
            return List.of();
        }
    }

    /**
     * GET /history/historic-variable-instances?processInstanceId= — the variables of a
     * COMPLETED instance (runtime endpoints 404 after the end event — flowable-rest skill §2).
     */
    public FlowablePage listHistoricVariableInstances(
            EngineConfig engine, CallPriority priority, String processInstanceId, int size) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> uri.path("/history/historic-variable-instances")
                                .queryParam("processInstanceId", processInstanceId)
                                .queryParam("size", size)
                                .build())
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /** GET /history/historic-activity-instances — one timeline page, startTime ascending. */
    public FlowablePage listHistoricActivities(
            EngineConfig engine, CallPriority priority, String processInstanceId, int start, int size) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> uri.path("/history/historic-activity-instances")
                                .queryParam("processInstanceId", processInstanceId)
                                .queryParam("sort", "startTime")
                                .queryParam("order", "asc")
                                .queryParam("start", start)
                                .queryParam("size", size)
                                .build())
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /**
     * GET /runtime/event-subscriptions?processInstanceId= — what a waiting instance waits
     * FOR (message/signal names, SPEC §4 vitals). Not present on every engine version:
     * callers degrade to an empty list on client errors, never fail the vitals.
     */
    public FlowablePage listEventSubscriptions(
            EngineConfig engine, CallPriority priority, String processInstanceId, int size) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> uri.path("/runtime/event-subscriptions")
                                .queryParam("processInstanceId", processInstanceId)
                                .queryParam("size", size)
                                .build())
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /**
     * GET /history/historic-task-instances?processInstanceId= — the instance's user tasks,
     * COMPLETED and OPEN alike (an open task is a historic row with a null endTime). No
     * engine-side sort: the accepted sort fields differ across 6.x/7.x, so callers order
     * by start time in the BFF.
     */
    public FlowablePage listHistoricTaskInstances(
            EngineConfig engine, CallPriority priority, String processInstanceId, int size) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> uri.path("/history/historic-task-instances")
                                .queryParam("processInstanceId", processInstanceId)
                                .queryParam("size", size)
                                .build())
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /**
     * GET /runtime/tasks?processInstanceId= — the live task rows: suspension state,
     * delegation, claim time. Also the only leg that sees tasks on an engine whose task
     * history is dialed below audit level.
     */
    public FlowablePage listRuntimeTasks(
            EngineConfig engine, CallPriority priority, String processInstanceId, int size) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> uri.path("/runtime/tasks")
                                .queryParam("processInstanceId", processInstanceId)
                                .queryParam("size", size)
                                .build())
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /**
     * GET /runtime/tasks?assignee= — every OPEN task currently assigned to a person, across the
     * whole engine (not scoped to one instance). Feeds person-centric task search (#99, "what is
     * Bob sitting on"). Bounded by {@code size} — never an unpaged fetch.
     */
    public FlowablePage listTasksByAssignee(EngineConfig engine, CallPriority priority, String assignee, int size) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> uri.path("/runtime/tasks")
                                .queryParam("assignee", assignee)
                                .queryParam("size", size)
                                .build())
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /**
     * GET /runtime/tasks?candidateUser= — every OPEN task a person could CLAIM, across the whole
     * engine: unassigned tasks whose candidate users/groups include them (Flowable's own
     * IdentityService resolves group membership when configured; a bare candidate-user link
     * always matches). Feeds person-centric task search (#99). Bounded by {@code size}.
     */
    public FlowablePage listTasksByCandidateUser(
            EngineConfig engine, CallPriority priority, String candidateUser, int size) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> uri.path("/runtime/tasks")
                                .queryParam("candidateUser", candidateUser)
                                .queryParam("size", size)
                                .build())
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /** GET /history/historic-task-instances/{id} — task resolution incl. completed tasks. Null = unknown. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getHistoricTaskInstance(EngineConfig engine, CallPriority priority, String taskId) {
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri("/history/historic-task-instances/{id}", taskId)
                            .retrieve()
                            .body(Map.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /**
     * GET /repository/deployments/{deploymentId}/resourcedata/{resourceName} — the raw
     * BPMN 2.0 XML exactly as deployed (Stage 2 diagram). {@code resourceName} may contain
     * slashes ("processes/order.bpmn20.xml") — they are REAL path segments to Flowable, so
     * each segment is encoded separately (an encoded %2F would 400 on Tomcat).
     */
    public String deploymentResourceData(
            EngineConfig engine, CallPriority priority, String deploymentId, String resourceName) {
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri(uri -> uri.path("/repository/deployments/{id}/resourcedata/")
                                    .pathSegment(resourceName.split("/"))
                                    .build(deploymentId))
                            .retrieve()
                            .body(String.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /** GET /management/engine — health + version probe. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> engineInfo(EngineConfig engine, CallPriority priority) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri("/management/engine")
                        .retrieve()
                        .body(Map.class));
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

    /* ---------- M4 corrective-action calls (flowable-rest skill §4 catalog) ---------- */

    /** GET /management/{lane}/{jobId} — server-fresh job restatement before a job verb. Null = gone. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getJob(EngineConfig engine, CallPriority priority, JobLaneKind lane, String jobId) {
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri(lane.path + "/{jobId}", jobId)
                            .retrieve()
                            .body(Map.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /** POST /management/deadletter-jobs/{jobId} {"action":"move"} — retry: DLQ → executable queue. */
    public void moveDeadLetterJob(EngineConfig engine, CallPriority priority, String jobId) {
        guarded.run(
                engine,
                priority,
                () -> guarded.writeClient(engine)
                        .post()
                        .uri(JobLaneKind.DEADLETTER.path + "/{jobId}", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("action", "move"))
                        .retrieve()
                        .toBodilessEntity());
    }

    /** POST /management/timer-jobs/{jobId} {"action":"move"} — fire a timer now. */
    public void moveTimerJob(EngineConfig engine, CallPriority priority, String jobId) {
        guarded.run(
                engine,
                priority,
                () -> guarded.writeClient(engine)
                        .post()
                        .uri(JobLaneKind.TIMER.path + "/{jobId}", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("action", "move"))
                        .retrieve()
                        .toBodilessEntity());
    }

    /** DELETE /management/deadletter-jobs/{jobId} — discard a dead-letter job (orphans the execution). */
    public void deleteDeadLetterJob(EngineConfig engine, CallPriority priority, String jobId) {
        guarded.run(
                engine,
                priority,
                () -> guarded.writeClient(engine)
                        .delete()
                        .uri(JobLaneKind.DEADLETTER.path + "/{jobId}", jobId)
                        .retrieve()
                        .toBodilessEntity());
    }

    /** PUT /runtime/process-instances/{id} {"action":"suspend"|"activate"}. */
    public void suspendOrActivateInstance(
            EngineConfig engine, CallPriority priority, String processInstanceId, String action) {
        guarded.run(
                engine,
                priority,
                () -> guarded.writeClient(engine)
                        .put()
                        .uri("/runtime/process-instances/{id}", processInstanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("action", action))
                        .retrieve()
                        .toBodilessEntity());
    }

    /** DELETE /runtime/process-instances/{id}?deleteReason= — terminate/delete (irreversible). */
    public void deleteProcessInstance(
            EngineConfig engine, CallPriority priority, String processInstanceId, String deleteReason) {
        guarded.run(
                engine,
                priority,
                () -> guarded.writeClient(engine)
                        .delete()
                        .uri(uri -> uri.path("/runtime/process-instances/{id}")
                                .queryParam("deleteReason", deleteReason)
                                .build(processInstanceId))
                        .retrieve()
                        .toBodilessEntity());
    }

    /** GET /runtime/process-instances/{id}/variables/{name} — the CAS pre-read (typed row). Null = absent. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getInstanceVariable(
            EngineConfig engine, CallPriority priority, String processInstanceId, String name) {
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri("/runtime/process-instances/{id}/variables/{name}", processInstanceId, name)
                            .retrieve()
                            .body(Map.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /**
     * PUT /runtime/process-instances/{id}/variables/{name} — typed single-variable write.
     * The declared type is preserved by the caller (string-ifying an integer breaks
     * gateways downstream — flowable-rest skill §2).
     */
    public void putInstanceVariable(
            EngineConfig engine,
            CallPriority priority,
            String processInstanceId,
            String name,
            Map<String, Object> typedVariable) {
        guarded.run(
                engine,
                priority,
                () -> guarded.writeClient(engine)
                        .put()
                        .uri("/runtime/process-instances/{id}/variables/{name}", processInstanceId, name)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(typedVariable)
                        .retrieve()
                        .toBodilessEntity());
    }

    /**
     * GET /runtime/executions/{executionId}/variables/{name}?scope=local — the CAS pre-read
     * for an execution-local ("step-local") variable (SPEC §4a). {@code scope=local} means
     * the engine returns ONLY a variable declared ON this execution, never a process-scope
     * value shadowed down the tree — so the CAS reads the exact row the write will touch.
     * Null = no such local variable (404).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getExecutionVariable(
            EngineConfig engine, CallPriority priority, String executionId, String name) {
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri(uri -> uri.path("/runtime/executions/{id}/variables/{name}")
                                    .queryParam("scope", "local")
                                    .build(executionId, name))
                            .retrieve()
                            .body(Map.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /**
     * PUT /runtime/executions/{executionId}/variables/{name} — typed execution-local write.
     * The body carries {@code scope:"local"} so the engine writes the variable ON this
     * execution node rather than promoting it to process scope; the declared type is
     * preserved by the caller (flowable-rest §2).
     */
    public void putExecutionVariable(
            EngineConfig engine,
            CallPriority priority,
            String executionId,
            String name,
            Map<String, Object> typedVariable) {
        guarded.run(
                engine,
                priority,
                () -> guarded.writeClient(engine)
                        .put()
                        .uri("/runtime/executions/{id}/variables/{name}", executionId, name)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(typedVariable)
                        .retrieve()
                        .toBodilessEntity());
    }

    /** GET /runtime/tasks/{taskId} — server-fresh task restatement. Null = gone/completed. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTask(EngineConfig engine, CallPriority priority, String taskId) {
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri("/runtime/tasks/{taskId}", taskId)
                            .retrieve()
                            .body(Map.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /** POST /runtime/tasks/{taskId} {"action":"complete","variables":[…]} — complete with data. */
    public void completeTask(
            EngineConfig engine, CallPriority priority, String taskId, List<Map<String, Object>> variables) {
        Map<String, Object> body = variables == null || variables.isEmpty()
                ? Map.of("action", "complete")
                : Map.of("action", "complete", "variables", variables);
        guarded.run(
                engine,
                priority,
                () -> guarded.writeClient(engine)
                        .post()
                        .uri("/runtime/tasks/{taskId}", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity());
    }

    /**
     * PUT /runtime/tasks/{taskId} {"assignee": …} — reassign to a specific user, or clear
     * the assignee ({@code null}) so the task falls back to its candidate groups
     * (return-to-team). The key is sent even when the value is null, so Flowable treats it as
     * an explicit set-to-null rather than "leave unchanged" (flowable-rest §4).
     */
    public void setTaskAssignee(EngineConfig engine, CallPriority priority, String taskId, String assignee) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assignee", assignee);
        guarded.run(
                engine,
                priority,
                () -> guarded.writeClient(engine)
                        .put()
                        .uri("/runtime/tasks/{taskId}", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity());
    }

    /** GET /runtime/executions/{executionId} — server-fresh execution restatement. Null = gone. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getExecution(EngineConfig engine, CallPriority priority, String executionId) {
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri("/runtime/executions/{executionId}", executionId)
                            .retrieve()
                            .body(Map.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /**
     * POST /runtime/executions/{executionId} — deliver what a waiting execution is
     * blocked on: {"action":"messageEventReceived","messageName":…} /
     * {"action":"signalEventReceived","signalName":…} / {"action":"trigger"}.
     */
    public void executionAction(
            EngineConfig engine, CallPriority priority, String executionId, Map<String, Object> body) {
        guarded.run(
                engine,
                priority,
                () -> guarded.writeClient(engine)
                        .put()
                        .uri("/runtime/executions/{executionId}", executionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity());
    }

    /** GET /repository/process-definitions/{definitionId} — server-fresh definition restatement. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getProcessDefinition(EngineConfig engine, CallPriority priority, String definitionId) {
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri("/repository/process-definitions/{id}", definitionId)
                            .retrieve()
                            .body(Map.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /**
     * PUT /repository/process-definitions/{definitionId} {"action":"suspend"|"activate",
     * "includeProcessInstances":…} — the "bad deploy" brake (SPEC §5 tier 3).
     */
    public void suspendOrActivateDefinition(
            EngineConfig engine,
            CallPriority priority,
            String definitionId,
            String action,
            boolean includeProcessInstances) {
        guarded.run(
                engine,
                priority,
                () -> guarded.writeClient(engine)
                        .put()
                        .uri("/repository/process-definitions/{id}", definitionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("action", action, "includeProcessInstances", includeProcessInstances))
                        .retrieve()
                        .toBodilessEntity());
    }

    /* ---------- v1.1 flow-surgery calls (SPEC §5 tier 2, flowable-rest skill §4) ---------- */

    /**
     * GET /repository/process-definitions/{id}/model — the engine's parsed BpmnModel as
     * JSON. Element entries carry {@code loopCharacteristics} explicitly, which is the
     * authoritative multi-instance signal for the change-state guardrails. (Gateway TYPES
     * are NOT distinguishable in this serialization — parallel-gateway analysis reads the
     * deployed XML instead, see {@link #processDefinitionResourceData}.) Null = unknown id.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getProcessDefinitionModel(
            EngineConfig engine, CallPriority priority, String definitionId) {
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri("/repository/process-definitions/{id}/model", definitionId)
                            .retrieve()
                            .body(Map.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /**
     * GET /repository/process-definitions/{id}/resourcedata — the deployed BPMN XML by
     * DEFINITION id (no deployment/resource-name indirection). Used for the structural
     * analysis the /model JSON cannot express (gateway element types). Null = unknown id.
     */
    public String processDefinitionResourceData(EngineConfig engine, CallPriority priority, String definitionId) {
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri("/repository/process-definitions/{id}/resourcedata", definitionId)
                            .retrieve()
                            .body(String.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /**
     * POST /runtime/process-instances/{id}/change-state — the token move. The body is
     * EXACTLY the payload the preview showed the operator ({@code cancelActivityIds} /
     * {@code startActivityIds}); the service audits it verbatim before dispatch.
     */
    public void changeActivityState(
            EngineConfig engine, CallPriority priority, String processInstanceId, Map<String, Object> body) {
        guarded.run(
                engine,
                priority,
                () -> guarded.writeClient(engine)
                        .post()
                        .uri("/runtime/process-instances/{id}/change-state", processInstanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity());
    }

    /**
     * POST /runtime/process-instances/{id}/migrate — instance migration to another deployed
     * version of the same process key (P0 spike 2026-07-09: this is the ONLY migration route
     * exposed over REST — there is no validate endpoint). The body is the migration document
     * ({@code toProcessDefinitionId} + {@code activityMappings}), returns empty on success; the
     * engine rejects the WHOLE document atomically (HTTP 500 with a verbatim message) when a
     * required activity mapping is missing. The service audits the document verbatim before
     * dispatch and never retries.
     */
    public void migrateInstance(
            EngineConfig engine,
            CallPriority priority,
            String processInstanceId,
            Map<String, Object> migrationDocument) {
        guarded.run(
                engine,
                priority,
                () -> guarded.writeClient(engine)
                        .post()
                        .uri("/runtime/process-instances/{id}/migrate", processInstanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(migrationDocument)
                        .retrieve()
                        .toBodilessEntity());
    }

    /**
     * POST /runtime/process-instances — start an instance (restart-as-new). The body pins
     * either {@code processDefinitionId} (version pinned) or {@code processDefinitionKey}
     * (latest) — never both. Returns the engine's instance representation (the new id).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> startProcessInstance(
            EngineConfig engine, CallPriority priority, Map<String, Object> body) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.writeClient(engine)
                        .post()
                        .uri("/runtime/process-instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(Map.class));
    }
}
