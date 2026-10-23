package io.inspector.client;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient.JobLaneKind;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;

/** Historic, runtime, task, variable, and job reads. Moved from ProcessApiClient (issue #475). */
final class ProcessInstanceReadClient {

    /** Runtime variable collections answer a BARE JSON array, not the paged envelope. */
    private static final org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>> VARIABLE_LIST =
            new org.springframework.core.ParameterizedTypeReference<>() {};

    private final GuardedCaller guarded;

    ProcessInstanceReadClient(GuardedCaller guarded) {
        this.guarded = guarded;
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

    /** GET /runtime/process-instances/{id}/identitylinks. Empty when the instance is not running. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listRuntimeIdentityLinks(
            EngineConfig engine, CallPriority priority, String processInstanceId) {
        try {
            List<Map<String, Object>> rows = guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri("/runtime/process-instances/{id}/identitylinks", processInstanceId)
                            .retrieve()
                            .body(List.class));
            return rows == null ? List.of() : rows;
        } catch (HttpClientErrorException.NotFound e) {
            return List.of();
        }
    }

    /** GET /history/historic-process-instances/{id}/identitylinks. Empty on 404. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listHistoricIdentityLinks(
            EngineConfig engine, CallPriority priority, String processInstanceId) {
        try {
            List<Map<String, Object>> rows = guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri("/history/historic-process-instances/{id}/identitylinks", processInstanceId)
                            .retrieve()
                            .body(List.class));
            return rows == null ? List.of() : rows;
        } catch (HttpClientErrorException.NotFound e) {
            return List.of();
        }
    }

    /** POST /runtime/process-instances/{id}/identitylinks {"user","type"}. Users only. */
    public void addInstanceIdentityLink(
            EngineConfig engine, CallPriority priority, String processInstanceId, String user, String type) {
        guarded.run(
                engine,
                priority,
                "add-participant",
                () -> guarded.writeClient(engine)
                        .post()
                        .uri("/runtime/process-instances/{id}/identitylinks", processInstanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("user", user, "type", type))
                        .retrieve()
                        .toBodilessEntity());
    }

    /** DELETE /runtime/process-instances/{id}/identitylinks/users/{user}/{type}. */
    public void deleteInstanceIdentityLink(
            EngineConfig engine, CallPriority priority, String processInstanceId, String user, String type) {
        guarded.run(
                engine,
                priority,
                "remove-participant",
                () -> guarded.writeClient(engine)
                        .delete()
                        .uri(
                                "/runtime/process-instances/{id}/identitylinks/users/{user}/{type}",
                                processInstanceId,
                                user,
                                type)
                        .retrieve()
                        .toBodilessEntity());
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

    /** GET /runtime/tasks with the caller's query. Blank values are omitted. Bounded by {@code size}. */
    public FlowablePage listRuntimeTasks(
            EngineConfig engine, CallPriority priority, Map<String, String> query, int size) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> {
                            var builder = uri.path("/runtime/tasks").queryParam("size", size);
                            if (query != null) {
                                query.forEach((key, value) -> {
                                    if (value != null && !value.isBlank()) {
                                        builder.queryParam(key, value);
                                    }
                                });
                            }
                            return builder.build();
                        })
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

    /** GET a management document. Used by system info; 404 is the caller's problem. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(EngineConfig engine, CallPriority priority, String path) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine).get().uri(path).retrieve().body(Map.class));
    }

    public FlowablePage getPage(EngineConfig engine, CallPriority priority, String path) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine).get().uri(path).retrieve().body(FlowablePage.class));
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
}
