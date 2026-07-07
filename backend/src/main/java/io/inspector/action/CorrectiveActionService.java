package io.inspector.action;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditOutcome;
import io.inspector.audit.AuditService;
import io.inspector.audit.AuditUnavailableException;
import io.inspector.audit.ProtectedInstance;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.FlowableEngineClient;
import io.inspector.client.FlowableEngineClient.JobLaneKind;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
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
    private final FlowableEngineClient client;
    private final AuditService audit;
    private final RbacAuthorizer rbac;
    private final ProtectedInstanceRepository protectedInstances;

    public CorrectiveActionService(
            EngineRegistry registry,
            FlowableEngineClient client,
            AuditService audit,
            RbacAuthorizer rbac,
            ProtectedInstanceRepository protectedInstances) {
        this.registry = registry;
        this.client = client;
        this.audit = audit;
        this.rbac = rbac;
        this.protectedInstances = protectedInstances;
    }

    /** {@code targetId} is the processInstanceId for INSTANCE verbs, the definitionId for DEFINITION verbs. */
    public ActionResult execute(
            String engineId, String targetId, ActionVerb verb, ActionRequest request, Authentication auth) {
        EngineConfig engine = registry.require(engineId);

        // -- guards: everything above the audit insert refuses with "nothing happened" --
        requireRole(auth, verb, engineId);
        requireWritableEngine(engine, verb);
        requireUnprotectedOrAdmin(auth, engine, verb, targetId);
        String reason = normalizedReason(engine, verb, request);

        Target target = restateTarget(engine, verb, targetId, request);
        requireConfirmToken(engine, verb, target, request);

        // -- fail-closed audit gate (R-AUD-01): beyond this point the attempt is on record --
        AuditEntry entry = audit.beginPending(
                auth.getName(),
                engineId,
                blankToNull(engine.tenantId()),
                verb.targetKind() == ActionVerb.TargetKind.INSTANCE ? targetId : null,
                verb.path(),
                reason,
                blankToNull(request.ticketId()),
                target.auditPayload());

        // -- CAS pre-check (audited: the last shift must see the refused attempt) --
        if (verb == ActionVerb.EDIT_VARIABLE) {
            Object current = target.currentVariableValue();
            Object expected = request.variable().expectedOldValue();
            if (!sameValue(expected, current)) {
                audit.close(entry, AuditOutcome.failed, 409, "CAS conflict: current value differs", false);
                throw new CasConflictException(entry.getId(), request.variable().name(), current, expected);
            }
        }

        // -- the one engine call --
        try {
            dispatch(engine, verb, targetId, request);
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
            throw new EngineRejectedException(entry.getId(), e.getStatusCode().value(), body);
        } catch (ResourceAccessException e) {
            if (notDispatched(e)) {
                audit.close(entry, AuditOutcome.failed, null, "engine unreachable: " + e.getMessage(), false);
                throw new GuardRefusedException(
                        HttpStatus.BAD_GATEWAY,
                        "engine-unreachable",
                        "Engine '" + engineId + "' is unreachable — the action was not sent.");
            }
            // Timed out AFTER dispatch: the engine may have applied it. UNKNOWN, never re-fired.
            audit.close(entry, AuditOutcome.unknown, null, "no answer within write budget: " + e.getMessage(), false);
            throw new OutcomeUnknownException(entry.getId(), e);
        }

        // -- dual-write close-out (R-SEM-18): a failure HERE is the specialized error --
        audit.close(entry, AuditOutcome.ok, 200, null, true);
        return new ActionResult(
                entry.getId(), entry.getCorrelationId(), "ok", 200, deltaStatement(verb, targetId, request, target));
    }

    /* ------------------------------- guards ------------------------------- */

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
    private Target restateTarget(EngineConfig engine, ActionVerb verb, String targetId, ActionRequest request) {
        try {
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
        Map<String, Object> job = client.getJob(engine, lane, jobId);
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

    private Target instanceTarget(EngineConfig engine, String instanceId) {
        Map<String, Object> instance = client.getRuntimeProcessInstance(engine, instanceId);
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

    private Target variableTarget(EngineConfig engine, String instanceId, ActionRequest request) {
        ActionRequest.VariableEdit edit = requiredEdit(request);
        Map<String, Object> row = client.getInstanceVariable(engine, instanceId, edit.name());
        if (row == null) {
            throw new GuardRefusedException(
                    HttpStatus.NOT_FOUND,
                    "variable-not-found",
                    "Variable '" + edit.name() + "' does not exist on running instance " + instanceId
                            + " — edit-variable changes existing values only. Nothing happened.");
        }
        Object current = row.get("value");
        boolean secret = AuditService.isSecretName(edit.name());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "edit-variable/v1");
        payload.put("name", edit.name());
        payload.put("scope", "process");
        payload.put("valueType", edit.type() != null ? edit.type() : row.get("type"));
        payload.put("oldValue", secret ? AuditService.REDACTED : current);
        payload.put("expectedOldValue", secret ? AuditService.REDACTED : edit.expectedOldValue());
        payload.put("newValue", secret ? AuditService.REDACTED : edit.value());
        return new Target(payload, null, null, current);
    }

    private Target taskTarget(EngineConfig engine, String instanceId, String taskId, ActionRequest request) {
        Map<String, Object> task = client.getTask(engine, taskId);
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
        Map<String, Object> task = client.getTask(engine, taskId);
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
        Map<String, Object> execution = client.getExecution(engine, executionId);
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
        Map<String, Object> definition = client.getProcessDefinition(engine, definitionId);
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

    private void dispatch(EngineConfig engine, ActionVerb verb, String targetId, ActionRequest request) {
        switch (verb) {
            case RETRY_JOB -> client.moveDeadLetterJob(engine, request.jobId());
            case TRIGGER_TIMER -> client.moveTimerJob(engine, request.jobId());
            case DELETE_DEADLETTER -> client.deleteDeadLetterJob(engine, request.jobId());
            case SUSPEND -> client.suspendOrActivateInstance(engine, targetId, "suspend");
            case ACTIVATE -> client.suspendOrActivateInstance(engine, targetId, "activate");
            case TERMINATE_DELETE ->
                client.deleteProcessInstance(
                        engine,
                        targetId,
                        request.reason() != null ? request.reason() : "terminated via process-inspector");
            case EDIT_VARIABLE -> {
                ActionRequest.VariableEdit edit = request.variable();
                Map<String, Object> typed = new LinkedHashMap<>();
                typed.put("name", edit.name());
                if (edit.type() != null) {
                    typed.put("type", edit.type());
                }
                typed.put("value", edit.value());
                client.putInstanceVariable(engine, targetId, edit.name(), typed);
            }
            case COMPLETE_TASK -> client.completeTask(engine, request.taskId(), typedVariables(request));
            case REASSIGN_TASK ->
                client.setTaskAssignee(
                        engine, request.taskId(), request.assignee().strip());
            case UNASSIGN_TASK -> client.setTaskAssignee(engine, request.taskId(), null);
            case UNSTICK_EVENT -> client.executionAction(engine, request.executionId(), eventBody(request.event()));
            case SUSPEND_DEFINITION ->
                client.suspendOrActivateDefinition(
                        engine, targetId, "suspend", Boolean.TRUE.equals(request.includeProcessInstances()));
            case ACTIVATE_DEFINITION ->
                client.suspendOrActivateDefinition(
                        engine, targetId, "activate", Boolean.TRUE.equals(request.includeProcessInstances()));
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
            case SUSPEND -> "Instance " + targetId + " suspended; its jobs moved to the suspended lane.";
            case ACTIVATE -> "Instance " + targetId + " activated; suspended jobs returned to their queues.";
            case EDIT_VARIABLE -> {
                boolean secret = AuditService.isSecretName(request.variable().name());
                yield "Variable '" + request.variable().name() + "' changed from "
                        + (secret ? AuditService.REDACTED : target.currentVariableValue()) + " to "
                        + (secret ? AuditService.REDACTED : request.variable().value()) + ".";
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
                "Dead-letter job " + request.jobId() + " deleted. The execution is orphaned — the only rescue"
                        + " afterwards is change-state.";
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
