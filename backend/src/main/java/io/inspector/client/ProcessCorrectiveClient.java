package io.inspector.client;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient.JobLaneKind;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;

/** Corrective-action mutations (jobs, instances, variables, tasks, executions). Moved from ProcessApiClient (issue #475). */
final class ProcessCorrectiveClient {

    private final GuardedCaller guarded;

    ProcessCorrectiveClient(GuardedCaller guarded) {
        this.guarded = guarded;
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
                "move-deadletter",
                () -> guarded.writeClient(engine)
                        .post()
                        .uri(JobLaneKind.DEADLETTER.path + "/{jobId}", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("action", "move"))
                        .retrieve()
                        .toBodilessEntity());
    }

    /** POST /management/timer-jobs/{jobId} {"action":"move"} — fire a timer now. */
    public void rescheduleTimer(EngineConfig engine, CallPriority priority, String jobId, String dueDate) {
        guarded.run(
                engine,
                priority,
                "reschedule-timer",
                () -> guarded.writeClient(engine)
                        .post()
                        .uri(JobLaneKind.TIMER.path + "/{jobId}", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("action", "reschedule", "dueDate", dueDate))
                        .retrieve()
                        .toBodilessEntity());
    }

    public void moveDeadLetterToHistoryJob(EngineConfig engine, CallPriority priority, String jobId) {
        guarded.run(
                engine,
                priority,
                "move-to-history-job",
                () -> guarded.writeClient(engine)
                        .post()
                        .uri(JobLaneKind.DEADLETTER.path + "/{jobId}", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("action", "moveToHistoryJob"))
                        .retrieve()
                        .toBodilessEntity());
    }

    public void deleteJob(EngineConfig engine, CallPriority priority, JobLaneKind lane, String jobId) {
        guarded.run(
                engine,
                priority,
                "delete-job",
                () -> guarded.writeClient(engine)
                        .delete()
                        .uri(lane.path + "/{jobId}", jobId)
                        .retrieve()
                        .toBodilessEntity());
    }

    public void moveTimerJob(EngineConfig engine, CallPriority priority, String jobId) {
        guarded.run(
                engine,
                priority,
                "move-timer",
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
                "delete-deadletter",
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
                action,
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
                "delete-instance",
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
                "put-instance-variable",
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
                "put-execution-variable",
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
                "complete-task",
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
                "set-task-assignee",
                () -> guarded.writeClient(engine)
                        .put()
                        .uri("/runtime/tasks/{taskId}", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity());
    }

    /**
     * POST /runtime/tasks/{taskId} TaskActionRequest. Claim always sends {@code assignee}, including
     * null, so an empty assignee unclaims. Resolve omits it.
     */
    public void taskAction(EngineConfig engine, CallPriority priority, String taskId, String action, String assignee) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        if (assignee != null || "claim".equals(action)) {
            body.put("assignee", assignee);
        }
        guarded.run(
                engine,
                priority,
                "task-" + action,
                () -> guarded.writeClient(engine)
                        .post()
                        .uri("/runtime/tasks/{taskId}", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity());
    }

    /** PUT /runtime/tasks/{taskId} — only the keys present in {@code body} are changed. */
    public void putTask(EngineConfig engine, CallPriority priority, String taskId, Map<String, Object> body) {
        guarded.run(
                engine,
                priority,
                "update-task",
                () -> guarded.writeClient(engine)
                        .put()
                        .uri("/runtime/tasks/{taskId}", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity());
    }

    /** DELETE /runtime/tasks/{taskId}. */
    public void deleteTask(EngineConfig engine, CallPriority priority, String taskId) {
        guarded.run(
                engine,
                priority,
                "delete-task",
                () -> guarded.writeClient(engine)
                        .delete()
                        .uri("/runtime/tasks/{taskId}", taskId)
                        .retrieve()
                        .toBodilessEntity());
    }

    /** GET /runtime/tasks/{taskId}/identitylinks. Empty on 404. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listIdentityLinks(EngineConfig engine, CallPriority priority, String taskId) {
        try {
            List<Map<String, Object>> rows = guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri("/runtime/tasks/{taskId}/identitylinks", taskId)
                            .retrieve()
                            .body(List.class));
            return rows == null ? List.of() : rows;
        } catch (HttpClientErrorException.NotFound e) {
            return List.of();
        }
    }

    /** POST /runtime/tasks/{taskId}/identitylinks. {@code family} is users or groups. */
    public void addIdentityLink(
            EngineConfig engine, CallPriority priority, String taskId, String family, String identityId, String type) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groups".equals(family) ? "groupId" : "userId", identityId);
        body.put("type", type);
        guarded.run(
                engine,
                priority,
                "add-identity-link",
                () -> guarded.writeClient(engine)
                        .post()
                        .uri("/runtime/tasks/{taskId}/identitylinks", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity());
    }

    /** DELETE /runtime/tasks/{taskId}/identitylinks/{family}/{identityId}/{type}. */
    public void deleteIdentityLink(
            EngineConfig engine, CallPriority priority, String taskId, String family, String identityId, String type) {
        guarded.run(
                engine,
                priority,
                "delete-identity-link",
                () -> guarded.writeClient(engine)
                        .delete()
                        .uri(
                                "/runtime/tasks/{taskId}/identitylinks/{family}/{identityId}/{type}",
                                taskId,
                                family,
                                identityId,
                                type)
                        .retrieve()
                        .toBodilessEntity());
    }

    /**
     * GET /identity/{family}/{id}. False only on 404. Any other error propagates so a present
     * IDM that refuses the read is not reported as "user missing".
     */
    public boolean idmIdentityExists(EngineConfig engine, CallPriority priority, String family, String identityId) {
        try {
            guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri("/identity/{family}/{identityId}", family, identityId)
                            .retrieve()
                            .toBodilessEntity());
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    /**
     * Capability probe: does this REST app bundle IDM? 200 = yes. Any 4xx (the route is absent
     * on a process-only app) = no. A 404 is "IDM not installed", never "user does not exist".
     */
    public boolean probeIdm(EngineConfig engine, CallPriority priority) {
        try {
            guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri(uri -> uri.path("/identity/users")
                                    .queryParam("size", 1)
                                    .build())
                            .retrieve()
                            .toBodilessEntity());
            return true;
        } catch (HttpClientErrorException e) {
            return false;
        }
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
        Object action = body.get("action");
        String verb = action instanceof String s && !s.isBlank() ? s : "execution-action";
        guarded.run(
                engine,
                priority,
                verb,
                () -> guarded.writeClient(engine)
                        .put()
                        .uri("/runtime/executions/{executionId}", executionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity());
    }
}
