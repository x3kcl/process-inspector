package io.inspector.client;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;

/** Process-definition, deployment, and flow-surgery calls. Moved from ProcessApiClient (issue #475). */
final class ProcessRepositoryClient {

    private final GuardedCaller guarded;

    ProcessRepositoryClient(GuardedCaller guarded) {
        this.guarded = guarded;
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
        suspendOrActivateDefinition(engine, priority, definitionId, action, includeProcessInstances, null);
    }

    /**
     * Same PUT, with an optional ISO-8601 {@code date} so the engine schedules the suspend or
     * activate instead of applying it on this call (#483 S6). Null date omits the field.
     */
    public void suspendOrActivateDefinition(
            EngineConfig engine,
            CallPriority priority,
            String definitionId,
            String action,
            boolean includeProcessInstances,
            String effectiveAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        body.put("includeProcessInstances", includeProcessInstances);
        if (effectiveAt != null) {
            body.put("date", effectiveAt);
        }
        guarded.run(
                engine,
                priority,
                action,
                () -> guarded.writeClient(engine)
                        .put()
                        .uri("/repository/process-definitions/{id}", definitionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
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
                "change-state",
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
                "migrate",
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
                "start-instance",
                () -> guarded.writeClient(engine)
                        .post()
                        .uri("/runtime/process-instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(Map.class));
    }

    /**
     * GET /repository/process-definitions — the catalog list. Blank filters are omitted so the
     * engine does not treat an empty string as a match. {@code latest} null means every version.
     */
    public FlowablePage listProcessDefinitions(
            EngineConfig engine,
            CallPriority priority,
            String key,
            String nameLike,
            Boolean latest,
            String deploymentId,
            int start,
            int size) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> {
                            var b = uri.path("/repository/process-definitions").queryParam("size", size);
                            if (start > 0) {
                                b.queryParam("start", start);
                            }
                            if (key != null && !key.isBlank()) {
                                b.queryParam("key", key);
                            }
                            if (nameLike != null && !nameLike.isBlank()) {
                                b.queryParam("nameLike", nameLike);
                            }
                            if (latest != null) {
                                b.queryParam("latest", latest);
                            }
                            if (deploymentId != null && !deploymentId.isBlank()) {
                                b.queryParam("deploymentId", deploymentId);
                            }
                            return b.build();
                        })
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /** GET /runtime/event-subscriptions?processDefinitionId= — start and instance subscriptions. */
    public FlowablePage listDefinitionEventSubscriptions(
            EngineConfig engine, CallPriority priority, String processDefinitionId, int size) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> uri.path("/runtime/event-subscriptions")
                                .queryParam("processDefinitionId", processDefinitionId)
                                .queryParam("size", size)
                                .build())
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /** GET /repository/deployments — name filter and newest-first. */
    public FlowablePage listDeployments(
            EngineConfig engine, CallPriority priority, String nameLike, int start, int size) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri(uri -> {
                            var b = uri.path("/repository/deployments")
                                    .queryParam("sort", "deployTime")
                                    .queryParam("order", "desc")
                                    .queryParam("size", size);
                            if (start > 0) {
                                b.queryParam("start", start);
                            }
                            if (nameLike != null && !nameLike.isBlank()) {
                                b.queryParam("nameLike", nameLike);
                            }
                            return b.build();
                        })
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /** GET /repository/deployments/{id}. Null when the engine answers 404. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDeployment(EngineConfig engine, CallPriority priority, String deploymentId) {
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri("/repository/deployments/{id}", deploymentId)
                            .retrieve()
                            .body(Map.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /** GET /repository/deployments/{id}/resources. */
    public FlowablePage listDeploymentResources(EngineConfig engine, CallPriority priority, String deploymentId) {
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine)
                        .get()
                        .uri("/repository/deployments/{id}/resources", deploymentId)
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /**
     * DELETE /repository/deployments/{id}?cascade= — irreversible. Not retried. {@code cascade}
     * also deletes runtime instances (and, on the engine, their history).
     */
    public void deleteDeployment(EngineConfig engine, CallPriority priority, String deploymentId, boolean cascade) {
        guarded.run(
                engine,
                priority,
                "delete-deployment",
                () -> guarded.writeClient(engine)
                        .delete()
                        .uri(uri -> uri.path("/repository/deployments/{id}")
                                .queryParam("cascade", cascade)
                                .build(deploymentId))
                        .retrieve()
                        .toBodilessEntity());
    }
}
