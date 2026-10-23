package io.inspector.client;

import io.inspector.action.GuardRefusedException;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.EngineApiContexts;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The CMMN facade (Engine-client split, #86 — F2/F9): case/plan-item/job queries and dead-letter-job
 * mutations against the {@code /cmmn-api} sibling context. Every method takes {@link CallPriority}
 * explicitly — the old god-class hardcoded {@code INTERACTIVE} here with no escape hatch, one of the
 * two call sites the split issue named directly.
 */
@Component
public class CmmnApiClient {

    private final GuardedCaller guarded;

    public CmmnApiClient(GuardedCaller guarded) {
        this.guarded = guarded;
    }

    /**
     * The CMMN Management/Runtime/Repository/History REST APIs live under the {@code /cmmn-api}
     * SIBLING of the registered process-API base — beside it, never under it. Which segment it
     * sits beside depends on the engine's deployment layout ({@code …/flowable-rest/service} for
     * the standalone war, {@code …/process-api} for an embedded Spring Boot engine), so the
     * derivation is shared with {@link ExternalJobApiClient#externalJobApiBase} and the registry's
     * pinned result — see {@link EngineApiContexts}.
     */
    static String cmmnApiBase(EngineConfig engine) {
        return EngineApiContexts.cmmnApi(engine.baseUrl());
    }

    /**
     * CMMN dead-letter jobs (Case Inspector Phase 1) — the failed async jobs of a co-deployed
     * CMMN engine. Like the external-job-api, the CMMN Management REST API is a SIBLING of the
     * process-api {@code /service} context, at {@code …/cmmn-api} (proven live: this list also
     * projects BPMN jobs with a null case attribution — the CMMN ones carry a non-null
     * {@code caseInstanceId}). Unlike the process-api DLQ (which ignores every scope param),
     * this endpoint HONORS {@code ?scopeType=cmmn} (live-proven 2026-07-08), so callers pass it
     * in {@code filters} to spend the scan cap on CMMN rows only. Callers MUST capability-gate
     * ({@code scopeType}, Flowable ≥ 6.8) first: on 6.3.1 the cmmn context exists but is
     * dead-letter-blind (spike Q3), so a call there would silently return a wrong view.
     */
    public FlowablePage listCmmnDeadLetterJobs(
            EngineConfig engine, CallPriority priority, Map<String, String> filters, int start, int size) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(
                        cmmnApiBase(engine) + "/cmmn-management/deadletter-jobs")
                .queryParam("start", start)
                .queryParam("size", size);
        filters.forEach(b::queryParam);
        java.net.URI uri = b.build().toUri();
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine).get().uri(uri).retrieve().body(FlowablePage.class));
    }

    /**
     * GET /cmmn-api/cmmn-management/deadletter-jobs/{id} — one CMMN dead-letter job by id, the
     * server-fresh restatement behind the Phase-3 retry action. By-id hydration is NOT subject to
     * the list scan cap (spike 2026-07-08), so it always returns the full case context
     * ({@code caseInstanceId}/{@code caseDefinitionId}/{@code planItemInstanceId}/
     * {@code elementId}/{@code exceptionMessage}). Null on 404 — the job already left the DLQ
     * (retried, fired or deleted). NB: the list row's own {@code url} points at the EXECUTABLE
     * {@code /cmmn-management/jobs/{id}} table and 404s for a dead-letter job — this constructs the
     * {@code deadletter-jobs/{id}} path itself. Callers capability-gate ({@code scopeType}, ≥ 6.8)
     * first.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getCmmnDeadLetterJob(EngineConfig engine, CallPriority priority, String jobId) {
        String id = GuardedCaller.safeId(jobId);
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri(cmmnApiBase(engine) + "/cmmn-management/deadletter-jobs/{id}", id)
                            .retrieve()
                            .body(Map.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /**
     * GET /cmmn-api/cmmn-repository/case-definitions/{id} — resolves a CMMN
     * {@code caseDefinitionId} (a bare uuid on job rows, unlike a BPMN
     * {@code key:version:uuid}) to its readable {@code key}/{@code name}/{@code version}.
     * Null when the id is unknown (404) — a definition can be undeployed while its
     * dead-letter jobs linger. Callers resolve DISTINCT ids only (never per job row).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getCmmnCaseDefinition(
            EngineConfig engine, CallPriority priority, String caseDefinitionId) {
        String id = GuardedCaller.safeId(caseDefinitionId);
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri(cmmnApiBase(engine) + "/cmmn-repository/case-definitions/{id}", id)
                            .retrieve()
                            .body(Map.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /**
     * GET /cmmn-api/cmmn-runtime/case-instances/{id} — a RUNNING case by id (the omnibox's CMMN
     * resolution leg, R-SEM-04). A dead-lettered async job keeps its case active, so the 3am
     * paste of a Case id from the out-of-scope drawer lands here. Null on 404 (not running — try
     * {@link #getHistoricCmmnCaseInstance}). Callers capability-gate (≥ 6.8) first.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getCmmnCaseInstance(EngineConfig engine, CallPriority priority, String caseInstanceId) {
        String id = GuardedCaller.safeId(caseInstanceId);
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri(cmmnApiBase(engine) + "/cmmn-runtime/case-instances/{id}", id)
                            .retrieve()
                            .body(Map.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listCaseIdentityLinks(
            EngineConfig engine, CallPriority priority, String caseInstanceId) {
        String id = GuardedCaller.safeId(caseInstanceId);
        try {
            List<Map<String, Object>> rows = guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri(cmmnApiBase(engine) + "/cmmn-runtime/case-instances/{id}/identitylinks", id)
                            .retrieve()
                            .body(List.class));
            return rows == null ? List.of() : rows;
        } catch (HttpClientErrorException.NotFound e) {
            return List.of();
        }
    }

    public void addCaseIdentityLink(
            EngineConfig engine, CallPriority priority, String caseInstanceId, String user, String type) {
        String id = GuardedCaller.safeId(caseInstanceId);
        guarded.run(
                engine,
                priority,
                "add-participant",
                () -> guarded.writeClient(engine)
                        .post()
                        .uri(cmmnApiBase(engine) + "/cmmn-runtime/case-instances/{id}/identitylinks", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("user", user, "type", type))
                        .retrieve()
                        .toBodilessEntity());
    }

    public FlowablePage listFinishedCases(
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
                        .uri(UriComponentsBuilder.fromUriString(
                                        cmmnApiBase(engine) + "/cmmn-history/historic-case-instances")
                                .queryParam("finished", "true")
                                .queryParam("finishedBefore", finishedBefore)
                                .queryParam("size", size)
                                .queryParam("start", start)
                                .queryParamIfPresent(
                                        "caseDefinitionKey", java.util.Optional.ofNullable(blankToNull(definitionKey)))
                                .queryParamIfPresent(
                                        "businessKey", java.util.Optional.ofNullable(blankToNull(businessKey)))
                                .build(true)
                                .toUri())
                        .retrieve()
                        .body(FlowablePage.class));
    }

    /** DELETE historic case. This route takes no delete reason. */
    public void deleteHistoricCase(EngineConfig engine, CallPriority priority, String caseInstanceId) {
        String id = GuardedCaller.safeId(caseInstanceId);
        guarded.run(
                engine,
                priority,
                "delete-case-history",
                () -> guarded.writeClient(engine)
                        .delete()
                        .uri(cmmnApiBase(engine) + "/cmmn-history/historic-case-instances/{id}", id)
                        .retrieve()
                        .toBodilessEntity());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public void planItemAction(EngineConfig engine, CallPriority priority, String planItemInstanceId, String action) {
        String id = GuardedCaller.safeId(planItemInstanceId);
        guarded.run(
                engine,
                priority,
                "plan-item-" + action,
                () -> guarded.writeClient(engine)
                        .put()
                        .uri(cmmnApiBase(engine) + "/cmmn-runtime/plan-item-instances/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("action", action))
                        .retrieve()
                        .toBodilessEntity());
    }

    public void changePlanItemState(
            EngineConfig engine, CallPriority priority, String caseInstanceId, String definitionId, String action) {
        String id = GuardedCaller.safeId(caseInstanceId);
        String field =
                switch (action) {
                    case "activate" -> "activatePlanItemDefinitionIds";
                    case "available" -> "moveToAvailablePlanItemDefinitionIds";
                    case "terminate" -> "terminatePlanItemDefinitionIds";
                    default -> throw new IllegalArgumentException(action);
                };
        guarded.run(
                engine,
                priority,
                "change-plan-item",
                () -> guarded.writeClient(engine)
                        .post()
                        .uri(cmmnApiBase(engine) + "/cmmn-runtime/case-instances/{id}/change-state", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(field, List.of(definitionId)))
                        .retrieve()
                        .toBodilessEntity());
    }

    public void terminateCase(EngineConfig engine, CallPriority priority, String caseInstanceId) {
        String id = GuardedCaller.safeId(caseInstanceId);
        guarded.run(
                engine,
                priority,
                "terminate-case",
                () -> guarded.writeClient(engine)
                        .delete()
                        .uri(cmmnApiBase(engine) + "/cmmn-runtime/case-instances/{id}", id)
                        .retrieve()
                        .toBodilessEntity());
    }

    public void deleteCase(EngineConfig engine, CallPriority priority, String caseInstanceId) {
        String id = GuardedCaller.safeId(caseInstanceId);
        guarded.run(
                engine,
                priority,
                "delete-case",
                () -> guarded.writeClient(engine)
                        .delete()
                        .uri(cmmnApiBase(engine) + "/cmmn-runtime/case-instances/{id}/delete", id)
                        .retrieve()
                        .toBodilessEntity());
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listCaseVariables(
            EngineConfig engine, CallPriority priority, String caseInstanceId) {
        String id = GuardedCaller.safeId(caseInstanceId);
        try {
            List<Map<String, Object>> rows = guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri(cmmnApiBase(engine) + "/cmmn-runtime/case-instances/{id}/variables", id)
                            .retrieve()
                            .body(List.class));
            return rows == null ? List.of() : rows;
        } catch (HttpClientErrorException.NotFound e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getCaseVariable(
            EngineConfig engine, CallPriority priority, String caseInstanceId, String name) {
        String id = GuardedCaller.safeId(caseInstanceId);
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri(cmmnApiBase(engine) + "/cmmn-runtime/case-instances/{id}/variables/{name}", id, name)
                            .retrieve()
                            .body(Map.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    public void putCaseVariable(
            EngineConfig engine, CallPriority priority, String caseInstanceId, String name, String type, Object value) {
        String id = GuardedCaller.safeId(caseInstanceId);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", name);
        if (type != null) {
            body.put("type", type);
        }
        body.put("value", value);
        guarded.run(
                engine,
                priority,
                "edit-case-variable",
                () -> guarded.writeClient(engine)
                        .put()
                        .uri(cmmnApiBase(engine) + "/cmmn-runtime/case-instances/{id}/variables/{name}", id, name)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity());
    }

    public void deleteCaseIdentityLink(
            EngineConfig engine, CallPriority priority, String caseInstanceId, String user, String type) {
        String id = GuardedCaller.safeId(caseInstanceId);
        guarded.run(
                engine,
                priority,
                "remove-participant",
                () -> guarded.writeClient(engine)
                        .delete()
                        .uri(
                                cmmnApiBase(engine)
                                        + "/cmmn-runtime/case-instances/{id}/identitylinks/users/{user}/{type}",
                                id,
                                user,
                                type)
                        .retrieve()
                        .toBodilessEntity());
    }

    /**
     * GET /cmmn-api/cmmn-history/historic-case-instances/{id} — an ENDED case by id (completed or
     * terminated), so a resolve of a finished case is answered truthfully rather than as "not
     * found". Its DTO parallels historic-process-instances (businessKey/startTime/endTime/
     * caseDefinitionId+Name; spike Q2). Null on 404. Callers capability-gate (≥ 6.8) first.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getHistoricCmmnCaseInstance(
            EngineConfig engine, CallPriority priority, String caseInstanceId) {
        String id = GuardedCaller.safeId(caseInstanceId);
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine)
                            .get()
                            .uri(cmmnApiBase(engine) + "/cmmn-history/historic-case-instances/{id}", id)
                            .retrieve()
                            .body(Map.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /**
     * Count-only ({@code size=1}) CMMN historic case-instances in one lifecycle state — the
     * Case Inspector's scope-typed lane facet (ACTIVE / COMPLETED / TERMINATED; a case cannot
     * SUSPEND, spike Q2). {@code state} maps straight onto the collection's {@code ?state=}
     * param, live-proven honored on 6.8+ (silently ignored on 6.3.1, so callers gate ≥ 6.8);
     * {@code filters} threads {@code tenantId}. Returns the page {@code total} — never the rows
     * (Stage-0 aggregations are count-only, iron rule). 0 on an unreachable/degraded page.
     */
    public long countHistoricCmmnCaseInstances(
            EngineConfig engine, CallPriority priority, String state, Map<String, String> filters) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(
                        cmmnApiBase(engine) + "/cmmn-history/historic-case-instances")
                .queryParam("state", state)
                .queryParam("size", 1);
        filters.forEach(b::queryParam);
        java.net.URI uri = b.build().toUri();
        FlowablePage page = guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine).get().uri(uri).retrieve().body(FlowablePage.class));
        return page != null ? page.total() : 0;
    }

    /** Cases started in the window. Null when the CMMN history route is missing (pre-6.8). */
    public Long countCasesStarted(EngineConfig engine, CallPriority priority, Instant after, Instant before) {
        String afterIso =
                after.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        String beforeIso =
                before.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        java.net.URI uri = UriComponentsBuilder.fromUriString(
                        cmmnApiBase(engine) + "/cmmn-history/historic-case-instances")
                .queryParam("size", 1)
                .queryParam("startedAfter", afterIso)
                .queryParam("startedBefore", beforeIso)
                .build()
                .toUri();
        try {
            FlowablePage page = guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine).get().uri(uri).retrieve().body(FlowablePage.class));
            return page != null ? page.total() : 0L;
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /**
     * GET /cmmn-api/cmmn-runtime/plan-item-instances?caseInstanceId= — the plan items of ONE
     * running case (Case Inspector Phase 2 timeline). RUNTIME-ONLY: {@code
     * cmmn-history/historic-plan-item-instances} 404s on 6.8 (spike Q6), so an ENDED case has no
     * plan-item source at all — callers render the timeline as "unavailable for ended cases",
     * never a fabricated empty list. Each row carries {@code id} (the join key for a dead-letter
     * job's {@code planItemInstanceId}, spike Q7), {@code elementId} (the CMMN DI shape key —
     * NOT the same as a job row's {@code elementId}, which is the plan-item DEFINITION id),
     * {@code planItemDefinitionType}, {@code state}, {@code stageInstanceId}, and the lifecycle
     * timestamps. Callers capability-gate (≥ 6.8) first.
     */
    public FlowablePage listCmmnPlanItemInstances(
            EngineConfig engine, CallPriority priority, Map<String, String> filters, int start, int size) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(
                        cmmnApiBase(engine) + "/cmmn-runtime/plan-item-instances")
                .queryParam("start", start)
                .queryParam("size", size);
        filters.forEach(b::queryParam);
        java.net.URI uri = b.build().toUri();
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine).get().uri(uri).retrieve().body(FlowablePage.class));
    }

    /**
     * GET /cmmn-api/cmmn-management/jobs — the EXECUTABLE (not yet dead-lettered) CMMN jobs, the
     * source of the {@code RETRYING} plan-item annotation (a failing job with retries left) that
     * pairs with the dead-letter {@code FAILED} annotation (Case Inspector Phase 2). Joined to a
     * plan item by {@code planItemInstanceId}, exactly like the dead-letter list (spike Q7).
     * Callers capability-gate (≥ 6.8) first.
     */
    public FlowablePage listCmmnJobs(
            EngineConfig engine, CallPriority priority, Map<String, String> filters, int start, int size) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(cmmnApiBase(engine) + "/cmmn-management/jobs")
                .queryParam("start", start)
                .queryParam("size", size);
        filters.forEach(b::queryParam);
        java.net.URI uri = b.build().toUri();
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine).get().uri(uri).retrieve().body(FlowablePage.class));
    }

    /**
     * GET /cmmn-api/cmmn-repository/deployments/{deploymentId}/resourcedata/{resourceName} — the
     * raw CMMN 1.1 XML exactly as deployed (Phase 2 case diagram), the CMMN sibling of
     * {@link ProcessApiClient#deploymentResourceData}. {@code cmmn-js} needs the
     * {@code <cmmndi:CMMNDI>} block to render — a DI-less model imports to an empty canvas, so
     * callers pair this with the definition's {@code graphicalNotationDefined} flag and degrade
     * honestly. Null on 404.
     */
    public String cmmnDeploymentResourceData(
            EngineConfig engine, CallPriority priority, String deploymentId, String resourceName) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(cmmnApiBase(engine)
                + "/cmmn-repository/deployments/" + GuardedCaller.safeId(deploymentId) + "/resourcedata");
        for (String segment : resourceName.split("/")) {
            b.pathSegment(segment);
        }
        java.net.URI uri = b.build().toUri();
        try {
            return guarded.call(
                    engine,
                    priority,
                    () -> guarded.readClient(engine).get().uri(uri).retrieve().body(String.class));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /**
     * POST /cmmn-api/cmmn-management/deadletter-jobs/{jobId} {"action":"move"} — the CMMN sibling
     * of {@code ProcessApiClient.moveDeadLetterJob}, byte-identical in shape (live-proven
     * 2026-07-08, HTTP 204): moves a CMMN dead-letter job back to the executable queue with
     * engine-default retries restored. The base path differs (the {@code /cmmn-api} context is a
     * sibling of {@code /service}, so this builds an absolute URI rather than the process-api
     * relative path). Callers capability-gate ({@code scopeType}, ≥ 6.8) first.
     */
    public void moveCmmnDeadLetterJob(EngineConfig engine, CallPriority priority, String jobId) {
        String id = GuardedCaller.safeId(jobId);
        guarded.run(
                engine,
                priority,
                "move-cmmn-deadletter",
                () -> guarded.writeClient(engine)
                        .post()
                        .uri(cmmnApiBase(engine) + "/cmmn-management/deadletter-jobs/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("action", "move"))
                        .retrieve()
                        .toBodilessEntity());
    }

    /**
     * DELETE /cmmn-api/cmmn-management/deadletter-jobs/{jobId} — the CMMN sibling of {@code
     * ProcessApiClient.deleteDeadLetterJob}, byte-identical in shape (live-proven 2026-07-08, HTTP
     * 204): discards a CMMN dead-letter job, orphaning its plan-item execution. The base path
     * differs (the {@code /cmmn-api} context is a sibling of {@code /service}, so this builds an
     * absolute URI). Callers capability-gate ({@code scopeType}, ≥ 6.8) first.
     */
    /**
     * GET /cmmn-repository/case-definitions — catalog sibling of the process-definition list.
     * Callers capability-gate ({@code scopeType}, ≥ 6.8) first.
     */
    public FlowablePage listCaseDefinitions(
            EngineConfig engine, CallPriority priority, String key, String nameLike, Boolean latest, int size) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(
                        cmmnApiBase(engine) + "/cmmn-repository/case-definitions")
                .queryParam("size", size);
        if (key != null && !key.isBlank()) {
            b.queryParam("key", key);
        }
        if (nameLike != null && !nameLike.isBlank()) {
            b.queryParam("nameLike", nameLike);
        }
        if (latest != null) {
            b.queryParam("latest", latest);
        }
        java.net.URI uri = b.build().toUri();
        return guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine).get().uri(uri).retrieve().body(FlowablePage.class));
    }

    /** Count-only running cases on one case definition ({@code size=1}, read {@code total}). */
    public long countRunningCases(EngineConfig engine, CallPriority priority, String caseDefinitionId) {
        java.net.URI uri = UriComponentsBuilder.fromUriString(cmmnApiBase(engine) + "/cmmn-runtime/case-instances")
                .queryParam("caseDefinitionId", caseDefinitionId)
                .queryParam("size", 1)
                .build()
                .toUri();
        FlowablePage page = guarded.call(
                engine,
                priority,
                () -> guarded.readClient(engine).get().uri(uri).retrieve().body(FlowablePage.class));
        return page != null ? page.total() : 0;
    }

    /**
     * One CMMN migration POST. {@link CaseMigrationRoute#SINGLE} is
     * {@code POST /cmmn-runtime/case-instances/{id}/migrate} (6.8 and 7.x). Historic and
     * by-definition routes throw {@link GuardRefusedException} {@code version-gate} on a non-7.x
     * engine before any HTTP — the advice maps that to a ProblemDetail naming the version gate.
     * Callers still capability-gate {@code scopeType} (≥ 6.8) first. Not retried.
     */
    public void migrateCase(
            EngineConfig engine,
            CallPriority priority,
            String engineVersion,
            CaseMigrationRoute route,
            String id,
            Map<String, Object> document) {
        if (route.requires7() && !flowable7(engineVersion)) {
            String shown = engineVersion == null || engineVersion.isBlank() ? "unknown" : engineVersion.trim();
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "version-gate",
                    "Case migration route " + route.path() + " is behind the Flowable 7.x version gate (engine is "
                            + shown + "). Refused before any engine call.");
        }
        String safe = GuardedCaller.safeId(id);
        guarded.run(
                engine,
                priority,
                "migrate-case",
                () -> guarded.writeClient(engine)
                        .post()
                        .uri(cmmnApiBase(engine) + route.path(), safe)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(document)
                        .retrieve()
                        .toBodilessEntity());
    }

    /** Major ≥ 7. Unparseable or missing versions fail closed (not 7.x). */
    static boolean flowable7(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        String major = version.trim().split("\\.", -1)[0];
        try {
            return Integer.parseInt(major) >= 7;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public void deleteCmmnDeadLetterJob(EngineConfig engine, CallPriority priority, String jobId) {
        String id = GuardedCaller.safeId(jobId);
        guarded.run(
                engine,
                priority,
                "delete-cmmn-deadletter",
                () -> guarded.writeClient(engine)
                        .delete()
                        .uri(cmmnApiBase(engine) + "/cmmn-management/deadletter-jobs/{id}", id)
                        .retrieve()
                        .toBodilessEntity());
    }
}
