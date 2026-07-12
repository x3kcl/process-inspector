package io.inspector.action;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditOutcome;
import io.inspector.audit.AuditService;
import io.inspector.audit.AuditUnavailableException;
import io.inspector.audit.BreakGlassActor;
import io.inspector.audit.ProtectedInstance;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.ForwardedActor;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessApiClient.JobLaneKind;
import io.inspector.cmmn.CmmnCapabilities;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import io.inspector.security.reauth.DangerousActionReauthGate;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The single-target verb executor (SPEC §5/§6, M4). Every call runs the SAME rails, in
 * this order — a verb that skips one is a review-blocking bug (corrective-actions skill):
 *
 * <ol>
 *   <li>RBAC — scoped role floor per verb (also mirrored in {@code @PreAuthorize});
 *   <li>engine gates — registered, enabled, not read-only (R-GOV-04);
 *   <li>dangerous-set freshness — tier-3 verbs re-authenticate a stale OIDC session (R-SAFE-07);
 *   <li>protected-instance guard (R-SAFE-05, ADMIN floor);
 *   <li>reason discipline (SPEC §6) + request-shape validation;
 *   <li>server-fresh target restatement + tier-3 prod typed token;
 *   <li><b>audit INSERT (PENDING) — fail-closed (R-AUD-01)</b>;
 *   <li>CAS pre-check (edit-variable, R-SEM-09);
 *   <li>ONE engine call — never auto-retried;
 *   <li>outcome close-out {@code ok|failed|unknown} (R-SEM-18 — a failed close after an
 *       engine success is "dispatched — outcome verification failed", never a plain 500).
 * </ol>
 */
@Service
public class CorrectiveActionService {

    private final EngineRegistry registry;
    private final ProcessApiClient client;
    private final CmmnApiClient cmmnClient;
    private final AuditService audit;
    private final RbacAuthorizer rbac;
    private final ProtectedInstanceRepository protectedInstances;
    private final TicketPolicy ticketPolicy;
    private final DangerousActionReauthGate reauth;

    public CorrectiveActionService(
            EngineRegistry registry,
            ProcessApiClient client,
            CmmnApiClient cmmnClient,
            AuditService audit,
            RbacAuthorizer rbac,
            ProtectedInstanceRepository protectedInstances,
            TicketPolicy ticketPolicy,
            DangerousActionReauthGate reauth) {
        this.registry = registry;
        this.client = client;
        this.cmmnClient = cmmnClient;
        this.audit = audit;
        this.rbac = rbac;
        this.protectedInstances = protectedInstances;
        this.ticketPolicy = ticketPolicy;
        this.reauth = reauth;
    }

    /**
     * BPMN-scoped execute — the process-instance and definition routes (bulk included). Delegates
     * to the scoped form; the {@code targetId} is the processInstanceId for INSTANCE verbs, the
     * definitionId for DEFINITION verbs.
     */
    public ActionResult execute(
            String engineId, String targetId, ActionVerb verb, ActionRequest request, Authentication auth) {
        return execute(ActionScope.BPMN, engineId, targetId, verb, request, auth);
    }

    /**
     * The scoped verb executor (Case Inspector Phase 3 added the {@code scope} seam). For
     * {@link ActionScope#CMMN} the {@code targetId} is a caseInstanceId and the job verbs read /
     * move the CMMN ({@code /cmmn-management}) DLQ projection instead of the process-api one; every
     * other rail is scope-neutral.
     */
    public ActionResult execute(
            ActionScope scope,
            String engineId,
            String targetId,
            ActionVerb verb,
            ActionRequest request,
            Authentication auth) {
        EngineConfig engine = registry.require(engineId);

        // -- guards: everything above the audit insert refuses with "nothing happened" --
        requireRole(auth, verb, engineId);
        requireWritableEngine(engine, verb);
        // Dangerous-set freshness (IDP-SECURITY.md §5, R-SAFE-07): a tier-3 verb on an OAuth2 session
        // re-authenticates when auth_time exceeds the bounded window (absent auth_time fails closed).
        // A PRE-CONDITION — refused with "nothing happened" BEFORE the reason/typed-token rails, so
        // the SPA re-auths at verb intent, never after the operator has typed the confirm token
        // (⚠️ support-lead). Dev/basic + break-glass sessions are exempt (DangerousActionReauthGate).
        // INVARIANT (⚠️ do not break): this method is ALSO the per-item executor for async bulk jobs.
        // Bulk carries NO tier-3 verb today, so this never fires on a bulk item; bulk's OWN freshness
        // gate runs ONCE at submit (BulkJobService#submit, S5d) — NOT here per persisted item: a bulk
        // job outlives its session (R-SEM-10), so a per-item challenge would 401 the tail of a long
        // fan-out. When tier-3 bulk lands (the tier-4 wizard) that submit-time gate already covers it.
        if (verb.tier() >= 3) {
            reauth.enforce(auth);
        }
        // CMMN scope is capability-gated (scopeType, Flowable ≥ 6.8) BEFORE any engine read — an
        // older engine is dead-letter-blind on the cmmn context, so a blind move would be
        // silently wrong (do-no-harm). Also rejects any verb not applicable to a CMMN case.
        if (scope == ActionScope.CMMN) {
            requireCmmnScope(engine, verb);
        }
        requireUnprotectedOrAdmin(auth, engine, verb, targetId);
        String reason = normalizedReason(engine, verb, request);
        String ticketId = ticketPolicy.validate(request.ticketId(), engine.environment());

        Target target = restateTarget(scope, engine, verb, targetId, request);
        requireConfirmToken(engine, verb, target, request);

        // -- break-glass marker (S7): set from the PASSED auth on the dispatching thread BEFORE the
        // audit row is written, so a bulk job submitted under a sealed-account session flags EVERY
        // per-item row breakGlass=true. On a bulk virtual-thread worker the SecurityContextHolder is
        // empty (identity is threaded, not inherited), so AuditService's context read alone would
        // silently drop the flag on the items. Cleared in the finally below (never leaks). On the
        // ordinary request thread this is redundant with the context read — harmless. --
        BreakGlassActor.set(rbac.isBreakGlass(auth));
        try {
            // -- fail-closed audit gate (R-AUD-01): beyond this point the attempt is on record --
            AuditEntry entry = audit.beginPending(
                    auth.getName(),
                    engineId,
                    blankToNull(engine.tenantId()),
                    verb.targetKind() == ActionVerb.TargetKind.INSTANCE ? targetId : null,
                    verb.path(),
                    reason,
                    ticketId,
                    target.auditPayload(),
                    engine.auditPayloadOrDefault());

            // -- CAS pre-check (audited: the last shift must see the refused attempt) --
            if (verb == ActionVerb.EDIT_VARIABLE) {
                Object current = target.currentVariableValue();
                Object expected = request.variable().expectedOldValue();
                if (!sameValue(expected, current)) {
                    audit.close(entry, AuditOutcome.failed, 409, "CAS conflict: current value differs", false);
                    throw new CasConflictException(
                            entry.getId(), request.variable().name(), current, expected);
                }
            }

            // -- the one engine call --
            // Forward the acting human (== this audit row's actor) to identity-forwarding engines, set on
            // the dispatching thread so bulk virtual-thread workers carry it too (M4-CLOSEOUT §2 / D2a).
            ForwardedActor.set(entry.forwardedIdentity());
            try {
                dispatch(scope, engine, verb, targetId, request);
            } catch (CallNotPermittedException | BulkheadFullException e) {
                // Do-no-harm: the breaker/bulkhead refused BEFORE any bytes left — nothing happened.
                audit.close(entry, AuditOutcome.failed, null, "refused: circuit open / bulkhead full", false);
                throw new GuardRefusedException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "engine-shedding-load",
                        "Engine '" + engineId + "' is shedding load (circuit open) — the action was not sent.");
            } catch (RestClientResponseException e) {
                String body = e.getResponseBodyAsString();
                audit.close(entry, AuditOutcome.failed, e.getStatusCode().value(), body, false);
                throw new EngineRejectedException(
                        entry.getId(), e.getStatusCode().value(), body);
            } catch (ResourceAccessException e) {
                if (notDispatched(e)) {
                    audit.close(entry, AuditOutcome.failed, null, "engine unreachable: " + e.getMessage(), false);
                    throw new GuardRefusedException(
                            HttpStatus.BAD_GATEWAY,
                            "engine-unreachable",
                            "Engine '" + engineId + "' is unreachable — the action was not sent.");
                }
                // Timed out AFTER dispatch: the engine may have applied it. UNKNOWN, never re-fired.
                audit.close(
                        entry, AuditOutcome.unknown, null, "no answer within write budget: " + e.getMessage(), false);
                throw new OutcomeUnknownException(entry.getId(), e);
            } finally {
                ForwardedActor.clear();
            }

            // -- dual-write close-out (R-SEM-18): a failure HERE is the specialized error --
            audit.close(entry, AuditOutcome.ok, 200, null, true);
            return new ActionResult(
                    entry.getId(),
                    entry.getCorrelationId(),
                    "ok",
                    200,
                    deltaStatement(verb, targetId, request, target));
        } finally {
            BreakGlassActor.clear();
        }
    }

    /* ------------------------------- guards ------------------------------- */

    /**
     * The verbs a CMMN case supports today (Case Inspector Phase 3): the two dead-letter verbs —
     * retry (tier 0 / RESPONDER) and delete (tier 3 / ADMIN). Both act on the {@code
     * /cmmn-management} DLQ projection; every other verb is process-instance shaped and refused.
     */
    private static final java.util.Set<ActionVerb> CMMN_VERBS =
            java.util.Set.of(ActionVerb.RETRY_JOB, ActionVerb.DELETE_DEADLETTER);

    /**
     * CMMN-scope gate: the verb must be CMMN-applicable AND the engine must advertise
     * {@code scopeType} (≥ 6.8). Both refuse before any engine bytes leave — an unsupported verb
     * or a dead-letter-blind engine never gets a blind call.
     */
    private void requireCmmnScope(EngineConfig engine, ActionVerb verb) {
        if (!CMMN_VERBS.contains(verb)) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "verb-not-cmmn-scoped",
                    "'" + verb.path() + "' is not available on a CMMN case — only dead-letter retry and delete are"
                            + " (Case Inspector Phase 3). Nothing happened.");
        }
        CmmnCapabilities.requireScopeType(registry, engine);
    }

    private void requireRole(Authentication auth, ActionVerb verb, String engineId) {
        if (!rbac.hasRoleOn(auth, verb.minRole(), engineId)) {
            throw new GuardRefusedException(
                    HttpStatus.FORBIDDEN,
                    "rbac-denied",
                    "'" + verb.path() + "' (tier " + verb.tier() + ") requires " + verb.minRole() + " on engine '"
                            + engineId + "'.");
        }
    }

    private void requireWritableEngine(EngineConfig engine, ActionVerb verb) {
        if (!engine.enabled()) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "engine-disabled",
                    "Engine '" + engine.id() + "' is disabled in the registry.");
        }
        if (engine.modeOrDefault() == EngineMode.READ_ONLY) {
            throw new GuardRefusedException(
                    HttpStatus.FORBIDDEN,
                    "engine-read-only",
                    "Engine '" + engine.id() + "' is registered read-only (R-GOV-04) — '" + verb.path()
                            + "' is rejected. Mutation rights are enabled per engine on the owning team's sign-off.");
        }
    }

    private void requireUnprotectedOrAdmin(Authentication auth, EngineConfig engine, ActionVerb verb, String targetId) {
        if (verb.targetKind() != ActionVerb.TargetKind.INSTANCE) {
            return;
        }
        Optional<ProtectedInstance> protection;
        try {
            protection = protectedInstances.findById(new ProtectedInstance.Key(engine.id(), targetId));
        } catch (RuntimeException e) {
            // The protection registry lives in the same Postgres as the audit log: if we
            // cannot check it, we cannot audit either — same fail-closed refusal (R-AUD-01).
            throw new AuditUnavailableException(e);
        }
        if (protection.isPresent() && !rbac.hasRoleOn(auth, Role.ADMIN, engine.id())) {
            throw new GuardRefusedException(
                    HttpStatus.FORBIDDEN,
                    "instance-protected",
                    "Instance " + engine.id() + ":" + targetId + " is protected (R-SAFE-05): \""
                            + protection.get().getReason() + "\" — L3 (ADMIN) action required.");
        }
    }

    /** SPEC §6 reason ladder: tiers ≥2 always required; tier 1 required on prod; ≥10 chars when present. */
    private String normalizedReason(EngineConfig engine, ActionVerb verb, ActionRequest request) {
        String reason = blankToNull(request.reason());
        boolean required = verb.tier() >= 2 || (verb.tier() == 1 && engine.environment() == EngineEnvironment.PROD);
        if (required && reason == null) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "reason-required",
                    "'" + verb.path() + "' (tier " + verb.tier() + ") requires a reason of at least 10 characters"
                            + (verb.tier() == 1 ? " on a prod engine." : "."));
        }
        if (reason != null && reason.length() < 10) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST, "reason-too-short", "The reason must be at least 10 characters.");
        }
        return reason;
    }

    /** Tier-3 on prod: target-specific typed token, verified against the SERVER-FRESH target (SPEC §6). */
    private void requireConfirmToken(EngineConfig engine, ActionVerb verb, Target target, ActionRequest request) {
        if (verb.tier() < 3 || engine.environment() != EngineEnvironment.PROD) {
            return;
        }
        String expected = target.confirmToken();
        if (expected == null || !expected.equals(request.confirmToken())) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "confirm-token-mismatch",
                    "Destructive action on a PROD engine: type the " + target.confirmTokenName() + " (\"" + expected
                            + "\") to confirm. Nothing happened.");
        }
    }

    /* --------------------- server-fresh target restatement --------------------- */

    /**
     * Every verb re-reads its target before acting: 404s become honest "already
     * gone/completed" refusals instead of engine-side surprises, the audit payload gets
     * real old values, and the tier-3 token is checked against live state — never the
     * grid snapshot.
     */
    private Target restateTarget(
            ActionScope scope, EngineConfig engine, ActionVerb verb, String targetId, ActionRequest request) {
        try {
            // CMMN scope reads the /cmmn-management DLQ projection (by-id, cap-free) instead of the
            // process-api one; the guard above has already narrowed the verb set to the two
            // dead-letter verbs (retry / delete), which share this by-id restatement.
            if (scope == ActionScope.CMMN) {
                return cmmnJobTarget(engine, targetId, required(request.jobId(), "jobId"), verb);
            }
            return switch (verb) {
                case RETRY_JOB, DELETE_DEADLETTER ->
                    jobTarget(engine, JobLaneKind.DEADLETTER, targetId, required(request.jobId(), "jobId"), verb);
                case TRIGGER_TIMER ->
                    jobTarget(engine, JobLaneKind.TIMER, targetId, required(request.jobId(), "jobId"), verb);
                case SUSPEND, ACTIVATE, TERMINATE_DELETE -> instanceTarget(engine, targetId);
                case EDIT_VARIABLE -> variableTarget(engine, targetId, request);
                case COMPLETE_TASK -> taskTarget(engine, targetId, required(request.taskId(), "taskId"), request);
                case REASSIGN_TASK, UNASSIGN_TASK ->
                    taskAssignTarget(engine, verb, targetId, required(request.taskId(), "taskId"), request);
                case UNSTICK_EVENT -> executionTarget(engine, targetId, request);
                case SUSPEND_DEFINITION, ACTIVATE_DEFINITION -> definitionTarget(engine, targetId, request);
            };
        } catch (CallNotPermittedException | BulkheadFullException | ResourceAccessException e) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_GATEWAY,
                    "engine-unreachable",
                    "Engine '" + engine.id() + "' did not answer the pre-flight read — the action was not sent.");
        }
    }

    private Target jobTarget(EngineConfig engine, JobLaneKind lane, String instanceId, String jobId, ActionVerb verb) {
        Map<String, Object> job = client.getJob(engine, CallPriority.INTERACTIVE, lane, jobId);
        if (job == null) {
            throw new GuardRefusedException(
                    HttpStatus.NOT_FOUND,
                    "job-gone",
                    "Job " + jobId + " is no longer in the " + lane.name().toLowerCase() + " lane"
                            + " (already retried, fired or deleted?). Nothing happened.");
        }
        Object owner = job.get("processInstanceId");
        if (owner != null && !instanceId.equals(String.valueOf(owner))) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "job-instance-mismatch",
                    "Job " + jobId + " belongs to instance " + owner + ", not " + instanceId + ".");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", verb.path() + "/v1");
        payload.put("jobId", jobId);
        payload.put("elementId", job.get("elementId"));
        payload.put("exceptionMessage", job.get("exceptionMessage"));
        return new Target(payload, jobId, "job id", null);
    }

    /**
     * CMMN sibling of {@link #jobTarget}: the server-fresh restatement of a CMMN dead-letter job,
     * read by-id from the {@code /cmmn-management} DLQ (cap-free hydration, spike 2026-07-08). A
     * null is the honest "already gone" refusal; ownership is checked on {@code caseInstanceId}
     * (the CMMN projection's owner key, where BPMN uses {@code processInstanceId}). The audit
     * payload records {@code scope:cmmn} and the case context so the delta is reconstructable.
     */
    private Target cmmnJobTarget(EngineConfig engine, String caseInstanceId, String jobId, ActionVerb verb) {
        Map<String, Object> job = cmmnClient.getCmmnDeadLetterJob(engine, CallPriority.INTERACTIVE, jobId);
        if (job == null) {
            throw new GuardRefusedException(
                    HttpStatus.NOT_FOUND,
                    "job-gone",
                    "CMMN dead-letter job " + jobId + " is no longer in the dead-letter lane"
                            + " (already retried, fired or deleted?). Nothing happened.");
        }
        Object owner = job.get("caseInstanceId");
        if (owner != null && !caseInstanceId.equals(String.valueOf(owner))) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "job-case-mismatch",
                    "Job " + jobId + " belongs to case " + owner + ", not " + caseInstanceId + ".");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", verb.path() + "/v1");
        payload.put("scope", "cmmn");
        payload.put("jobId", jobId);
        payload.put("caseInstanceId", caseInstanceId);
        payload.put("caseDefinitionId", job.get("caseDefinitionId"));
        payload.put("planItemInstanceId", job.get("planItemInstanceId"));
        payload.put("elementId", job.get("elementId"));
        payload.put("exceptionMessage", job.get("exceptionMessage"));
        return new Target(payload, jobId, "job id", null);
    }

    private Target instanceTarget(EngineConfig engine, String instanceId) {
        Map<String, Object> instance = client.getRuntimeProcessInstance(engine, CallPriority.INTERACTIVE, instanceId);
        if (instance == null) {
            throw new GuardRefusedException(
                    HttpStatus.NOT_FOUND,
                    "instance-not-running",
                    "Instance " + instanceId + " is not running on '" + engine.id()
                            + "' (completed or already deleted?). Nothing happened.");
        }
        String businessKey = instance.get("businessKey") != null ? String.valueOf(instance.get("businessKey")) : null;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("businessKey", businessKey);
        payload.put("processDefinitionId", instance.get("processDefinitionId"));
        payload.put("suspended", instance.get("suspended"));
        // Typed token = the business key; instances without one fall back to the instance id.
        String token = businessKey != null ? businessKey : instanceId;
        return new Target(payload, token, "business key", null);
    }

    /**
     * Server-fresh restatement of an edit-variable target, scope-aware. A blank
     * {@code executionId} is the process (case) scope — the pre-step-local path, read via
     * {@code /runtime/process-instances}. A non-blank one is the execution-local
     * ("step-local") scope (SPEC §4a): the execution is first re-read and ownership-checked
     * against the instance (a foreign execution id is refused, not blindly written), then the
     * variable is read {@code scope=local} so the CAS pre-check sees the exact row the write
     * will touch — never a process-scope value shadowed down the tree.
     */
    private Target variableTarget(EngineConfig engine, String instanceId, ActionRequest request) {
        ActionRequest.VariableEdit edit = requiredEdit(request);
        String executionId = blankToNull(edit.executionId());
        boolean local = executionId != null;

        String activityId = null;
        Map<String, Object> row;
        if (local) {
            Map<String, Object> execution = client.getExecution(engine, CallPriority.INTERACTIVE, executionId);
            if (execution == null) {
                throw new GuardRefusedException(
                        HttpStatus.NOT_FOUND,
                        "execution-gone",
                        "Execution " + executionId + " no longer exists (instance moved on?). Nothing happened.");
            }
            Object owner = execution.get("processInstanceId");
            if (owner != null && !instanceId.equals(String.valueOf(owner))) {
                throw new GuardRefusedException(
                        HttpStatus.BAD_REQUEST,
                        "execution-instance-mismatch",
                        "Execution " + executionId + " belongs to instance " + owner + ", not " + instanceId + ".");
            }
            activityId = execution.get("activityId") != null ? String.valueOf(execution.get("activityId")) : null;
            row = client.getExecutionVariable(engine, CallPriority.INTERACTIVE, executionId, edit.name());
            if (row == null) {
                throw new GuardRefusedException(
                        HttpStatus.NOT_FOUND,
                        "variable-not-found",
                        "Step-local variable '" + edit.name() + "' does not exist on execution " + executionId
                                + " — edit-variable changes existing values only. Nothing happened.");
            }
        } else {
            row = client.getInstanceVariable(engine, CallPriority.INTERACTIVE, instanceId, edit.name());
            if (row == null) {
                throw new GuardRefusedException(
                        HttpStatus.NOT_FOUND,
                        "variable-not-found",
                        "Variable '" + edit.name() + "' does not exist on running instance " + instanceId
                                + " — edit-variable changes existing values only. Nothing happened.");
            }
        }

        Object current = row.get("value");
        boolean secret = AuditService.isSecretName(edit.name());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "edit-variable/v1");
        payload.put("name", edit.name());
        payload.put("scope", local ? "local" : "process");
        if (local) {
            payload.put("executionId", executionId);
            payload.put("activityId", activityId);
        }
        payload.put("valueType", edit.type() != null ? edit.type() : row.get("type"));
        payload.put("oldValue", secret ? AuditService.REDACTED : current);
        payload.put("expectedOldValue", secret ? AuditService.REDACTED : edit.expectedOldValue());
        payload.put("newValue", secret ? AuditService.REDACTED : edit.value());
        return new Target(payload, null, null, current);
    }

    private Target taskTarget(EngineConfig engine, String instanceId, String taskId, ActionRequest request) {
        Map<String, Object> task = client.getTask(engine, CallPriority.INTERACTIVE, taskId);
        if (task == null) {
            throw new GuardRefusedException(
                    HttpStatus.NOT_FOUND,
                    "task-gone",
                    "Task " + taskId + " is not open (already completed?). Nothing happened.");
        }
        Object owner = task.get("processInstanceId");
        if (owner != null && !instanceId.equals(String.valueOf(owner))) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "task-instance-mismatch",
                    "Task " + taskId + " belongs to instance " + owner + ", not " + instanceId + ".");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "complete-task/v1");
        payload.put("taskId", taskId);
        payload.put("taskName", task.get("name"));
        payload.put("variables", auditVariables(request.variables()));
        return new Target(payload, taskId, "task id", null);
    }

    /**
     * Reassign / return-to-team restatement. {@code GET /runtime/tasks/{taskId}} returns only
     * OPEN tasks, so a null here IS the active-task gate — a completed task can no longer have
     * its assignee changed (R-SAFE: reassignment only on currently active user tasks). The
     * audit payload records the old and new assignee so the delta is reconstructable.
     */
    private Target taskAssignTarget(
            EngineConfig engine, ActionVerb verb, String instanceId, String taskId, ActionRequest request) {
        Map<String, Object> task = client.getTask(engine, CallPriority.INTERACTIVE, taskId);
        if (task == null) {
            throw new GuardRefusedException(
                    HttpStatus.NOT_FOUND,
                    "task-not-active",
                    "Task " + taskId + " is not an active user task (completed or already gone?) — an assignee can"
                            + " only be changed on a live task. Nothing happened.");
        }
        Object owner = task.get("processInstanceId");
        if (owner != null && !instanceId.equals(String.valueOf(owner))) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "task-instance-mismatch",
                    "Task " + taskId + " belongs to instance " + owner + ", not " + instanceId + ".");
        }
        String oldAssignee = task.get("assignee") != null ? String.valueOf(task.get("assignee")) : null;
        String newAssignee = verb == ActionVerb.REASSIGN_TASK
                ? required(request.assignee(), "assignee").strip()
                : null;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", verb.path() + "/v1");
        payload.put("taskId", taskId);
        payload.put("taskName", task.get("name"));
        payload.put("oldAssignee", oldAssignee);
        payload.put("newAssignee", newAssignee);
        return new Target(payload, taskId, "task id", null);
    }

    private Target executionTarget(EngineConfig engine, String instanceId, ActionRequest request) {
        String executionId = required(request.executionId(), "executionId");
        ActionRequest.EventTrigger event = request.event();
        if (event == null || blankToNull(event.type()) == null) {
            throw missingField("event.type (message | signal | trigger)");
        }
        boolean needsName = ActionRequest.EventTrigger.MESSAGE.equals(event.type())
                || ActionRequest.EventTrigger.SIGNAL.equals(event.type());
        if (needsName && blankToNull(event.name()) == null) {
            throw missingField("event.name");
        }
        Map<String, Object> execution = client.getExecution(engine, CallPriority.INTERACTIVE, executionId);
        if (execution == null) {
            throw new GuardRefusedException(
                    HttpStatus.NOT_FOUND,
                    "execution-gone",
                    "Execution " + executionId + " no longer exists (instance moved on?). Nothing happened.");
        }
        Object owner = execution.get("processInstanceId");
        if (owner != null && !instanceId.equals(String.valueOf(owner))) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "execution-instance-mismatch",
                    "Execution " + executionId + " belongs to instance " + owner + ", not " + instanceId + ".");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "unstick-event/v1");
        payload.put("executionId", executionId);
        payload.put("eventType", event.type());
        payload.put("eventName", event.name());
        payload.put("activityId", execution.get("activityId"));
        return new Target(payload, null, null, null);
    }

    private Target definitionTarget(EngineConfig engine, String definitionId, ActionRequest request) {
        Map<String, Object> definition = client.getProcessDefinition(engine, CallPriority.INTERACTIVE, definitionId);
        if (definition == null) {
            throw new GuardRefusedException(
                    HttpStatus.NOT_FOUND,
                    "definition-not-found",
                    "Process definition " + definitionId + " does not exist on '" + engine.id() + "'.");
        }
        String key = String.valueOf(definition.get("key"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "suspend-definition/v1");
        payload.put("definitionId", definitionId);
        payload.put("definitionKey", key);
        payload.put("version", definition.get("version"));
        payload.put("includeProcessInstances", Boolean.TRUE.equals(request.includeProcessInstances()));
        return new Target(payload, key, "definition key", null);
    }

    /* ------------------------------- dispatch ------------------------------- */

    private void dispatch(
            ActionScope scope, EngineConfig engine, ActionVerb verb, String targetId, ActionRequest request) {
        // CMMN scope: the guard above narrowed the verb set to the two DLQ verbs, both on the
        // /cmmn-management path — byte-identical bodies to the process-api ones, sibling context
        // (move + delete live-proven 2026-07-08, HTTP 204 each).
        if (scope == ActionScope.CMMN) {
            switch (verb) {
                case RETRY_JOB -> cmmnClient.moveCmmnDeadLetterJob(engine, CallPriority.INTERACTIVE, request.jobId());
                case DELETE_DEADLETTER ->
                    cmmnClient.deleteCmmnDeadLetterJob(engine, CallPriority.INTERACTIVE, request.jobId());
                default ->
                    throw new IllegalStateException("CMMN verb not dispatchable: " + verb); // unreachable: guarded
            }
            return;
        }
        switch (verb) {
            case RETRY_JOB -> client.moveDeadLetterJob(engine, CallPriority.INTERACTIVE, request.jobId());
            case TRIGGER_TIMER -> client.moveTimerJob(engine, CallPriority.INTERACTIVE, request.jobId());
            case DELETE_DEADLETTER -> client.deleteDeadLetterJob(engine, CallPriority.INTERACTIVE, request.jobId());
            case SUSPEND -> client.suspendOrActivateInstance(engine, CallPriority.INTERACTIVE, targetId, "suspend");
            case ACTIVATE -> client.suspendOrActivateInstance(engine, CallPriority.INTERACTIVE, targetId, "activate");
            case TERMINATE_DELETE ->
                client.deleteProcessInstance(
                        engine,
                        CallPriority.INTERACTIVE,
                        targetId,
                        request.reason() != null ? request.reason() : "terminated via process-inspector");
            case EDIT_VARIABLE -> {
                ActionRequest.VariableEdit edit = request.variable();
                String executionId = blankToNull(edit.executionId());
                Map<String, Object> typed = new LinkedHashMap<>();
                typed.put("name", edit.name());
                if (edit.type() != null) {
                    typed.put("type", edit.type());
                }
                typed.put("value", edit.value());
                if (executionId != null) {
                    // Step-local: write ON the execution node. scope=local keeps the engine from
                    // promoting it to process scope (SPEC §4a; flowable-rest §2).
                    typed.put("scope", "local");
                    client.putExecutionVariable(engine, CallPriority.INTERACTIVE, executionId, edit.name(), typed);
                } else {
                    client.putInstanceVariable(engine, CallPriority.INTERACTIVE, targetId, edit.name(), typed);
                }
            }
            case COMPLETE_TASK ->
                client.completeTask(engine, CallPriority.INTERACTIVE, request.taskId(), typedVariables(request));
            case REASSIGN_TASK ->
                client.setTaskAssignee(
                        engine,
                        CallPriority.INTERACTIVE,
                        request.taskId(),
                        request.assignee().strip());
            case UNASSIGN_TASK -> client.setTaskAssignee(engine, CallPriority.INTERACTIVE, request.taskId(), null);
            case UNSTICK_EVENT ->
                client.executionAction(
                        engine, CallPriority.INTERACTIVE, request.executionId(), eventBody(request.event()));
            case SUSPEND_DEFINITION ->
                client.suspendOrActivateDefinition(
                        engine,
                        CallPriority.INTERACTIVE,
                        targetId,
                        "suspend",
                        Boolean.TRUE.equals(request.includeProcessInstances()));
            case ACTIVATE_DEFINITION ->
                client.suspendOrActivateDefinition(
                        engine,
                        CallPriority.INTERACTIVE,
                        targetId,
                        "activate",
                        Boolean.TRUE.equals(request.includeProcessInstances()));
        }
    }

    private static Map<String, Object> eventBody(ActionRequest.EventTrigger event) {
        return switch (event.type()) {
            case ActionRequest.EventTrigger.MESSAGE ->
                Map.of("action", "messageEventReceived", "messageName", event.name());
            case ActionRequest.EventTrigger.SIGNAL ->
                Map.of("action", "signalEventReceived", "signalName", event.name());
            case ActionRequest.EventTrigger.TRIGGER -> Map.of("action", "trigger");
            default ->
                throw new GuardRefusedException(
                        HttpStatus.BAD_REQUEST,
                        "invalid-event-type",
                        "event.type must be message, signal or trigger — got '" + event.type() + "'.");
        };
    }

    private static List<Map<String, Object>> typedVariables(ActionRequest request) {
        if (request.variables() == null) {
            return List.of();
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (ActionRequest.TypedVariable variable : request.variables()) {
            Map<String, Object> typed = new LinkedHashMap<>();
            typed.put("name", variable.name());
            if (variable.type() != null) {
                typed.put("type", variable.type());
            }
            typed.put("value", variable.value());
            list.add(typed);
        }
        return list;
    }

    /* ------------------------------- helpers ------------------------------- */

    /** The explicit delta statement for the outcome toast (SPEC §6 tier 0 — never a bare "success"). */
    private static String deltaStatement(ActionVerb verb, String targetId, ActionRequest request, Target target) {
        return switch (verb) {
            case RETRY_JOB ->
                "Job " + request.jobId() + " moved back to the executable queue; engine-default retries restored."
                        + " The queue move is reversible; the side effects of the executed job are not.";
            case TRIGGER_TIMER -> "Timer job " + request.jobId() + " fired now and took its normal path.";
            // #117: never assert a job lane move here — suspend/activate touches executable
            // jobs only, a dead-letter job stays dead-lettered, and this path never reads
            // job lanes. State only what the call itself guarantees.
            case SUSPEND -> "Instance " + targetId + " suspended — reversible; Activate resumes it.";
            case ACTIVATE -> "Instance " + targetId + " activated — reversible; Suspend undoes it.";
            case EDIT_VARIABLE -> {
                ActionRequest.VariableEdit edit = request.variable();
                boolean secret = AuditService.isSecretName(edit.name());
                String scope = blankToNull(edit.executionId()) != null
                        ? "step-local variable (execution " + edit.executionId() + ")"
                        : "variable";
                yield capitalize(scope) + " '" + edit.name() + "' changed from "
                        + (secret ? AuditService.REDACTED : target.currentVariableValue()) + " to "
                        + (secret ? AuditService.REDACTED : edit.value()) + ".";
            }
            case COMPLETE_TASK ->
                "Task " + request.taskId() + " completed on the user's behalf with "
                        + (request.variables() == null ? 0 : request.variables().size())
                        + " variable override(s). A forced task never writes its own outputs.";
            case REASSIGN_TASK ->
                "Task " + request.taskId() + " reassigned to '"
                        + request.assignee().strip() + "' (was " + assigneeWas(target)
                        + "). The task stays where it is in the flow.";
            case UNASSIGN_TASK ->
                "Task " + request.taskId() + " returned to its team — assignee cleared (was " + assigneeWas(target)
                        + "); it now falls back to its candidate groups.";
            case UNSTICK_EVENT ->
                capitalize(request.event().type()) + " delivered to execution " + request.executionId()
                        + " — the wait is over.";
            case TERMINATE_DELETE -> "Instance " + targetId + " terminated and deleted — irreversible.";
            case DELETE_DEADLETTER ->
                "cmmn".equals(target.auditPayload().get("scope"))
                        ? "Dead-letter job " + request.jobId() + " deleted. The plan item is orphaned — the case"
                                + " cannot continue past this step on its own, and this tool offers no CMMN rescue"
                                + " verb (no change-state for cases)."
                        : "Dead-letter job " + request.jobId() + " deleted. The execution is orphaned — the only"
                                + " rescue afterwards is change-state.";
            case SUSPEND_DEFINITION ->
                "Definition " + targetId + " suspended — new instances are blocked"
                        + (Boolean.TRUE.equals(request.includeProcessInstances())
                                ? ", running instances suspended too."
                                : "; running instances continue.");
            case ACTIVATE_DEFINITION -> "Definition " + targetId + " activated again.";
        };
    }

    /** Old-assignee phrasing for the outcome toast — the server-fresh value captured at restatement. */
    private static String assigneeWas(Target target) {
        Object old = target.auditPayload().get("oldAssignee");
        return old != null ? "'" + old + "'" : "unassigned";
    }

    /** Not dispatched for sure: the connection itself failed — safe to call it `failed`. */
    private static boolean notDispatched(ResourceAccessException e) {
        for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException || t instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameValue(Object expected, Object actual) {
        if (expected instanceof Number e && actual instanceof Number a) {
            if (isIntegral(e) && isIntegral(a)) {
                return e.longValue() == a.longValue();
            }
            return Double.compare(e.doubleValue(), a.doubleValue()) == 0;
        }
        return Objects.equals(expected, actual);
    }

    private static boolean isIntegral(Number n) {
        return n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte;
    }

    private static ActionRequest.VariableEdit requiredEdit(ActionRequest request) {
        ActionRequest.VariableEdit edit = request.variable();
        if (edit == null || blankToNull(edit.name()) == null) {
            throw missingField("variable.name");
        }
        return edit;
    }

    private static String required(String value, String field) {
        if (blankToNull(value) == null) {
            throw missingField(field);
        }
        return value;
    }

    private static GuardRefusedException missingField(String field) {
        return new GuardRefusedException(
                HttpStatus.BAD_REQUEST, "missing-field", "This verb requires '" + field + "' in the request body.");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /** Audit copy of task-completion overrides: values of secret-named variables redacted. */
    private static List<Map<String, Object>> auditVariables(List<ActionRequest.TypedVariable> variables) {
        if (variables == null) {
            return List.of();
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (ActionRequest.TypedVariable variable : variables) {
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("name", variable.name());
            copy.put("type", variable.type());
            copy.put("value", AuditService.isSecretName(variable.name()) ? AuditService.REDACTED : variable.value());
            list.add(copy);
        }
        return list;
    }

    /**
     * Server-fresh restatement of the verb's target: the audit payload (old values
     * included), the tier-3 typed-token expectation, and the CAS current value.
     */
    private record Target(
            Map<String, Object> auditPayload,
            String confirmToken,
            String confirmTokenName,
            Object currentVariableValue) {}
}
