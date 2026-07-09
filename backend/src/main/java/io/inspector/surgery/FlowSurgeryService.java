package io.inspector.surgery;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.inspector.action.ActionResult;
import io.inspector.action.EngineRejectedException;
import io.inspector.action.GuardRefusedException;
import io.inspector.action.OutcomeUnknownException;
import io.inspector.action.TicketPolicy;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditOutcome;
import io.inspector.audit.AuditService;
import io.inspector.audit.AuditUnavailableException;
import io.inspector.audit.ProtectedInstance;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.FlowableEngineClient;
import io.inspector.client.FlowableEngineClient.FlowablePage;
import io.inspector.client.ForwardedActor;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * v1.1 flow surgery (SPEC §5 tier 2): change-state (token move) and restart-as-new.
 * Runs the SAME rail order as {@link io.inspector.action.CorrectiveActionService} —
 * RBAC → engine gates → protection → reason → server-fresh restatement → fail-closed
 * audit → ONE engine call, never retried → outcome close-out.
 *
 * <b>The simulation mandate:</b> Flowable's REST API has no dry-run for change-state
 * (SPEC §5 "explicitly not offered"). {@link #previewChangeState} is therefore a BFF
 * simulation: it runs every BLOCKING guard against live engine state and the deployed
 * model, and returns the EXACT JSON body execute will send. Execute re-plans server-fresh
 * — it never trusts a previously issued preview.
 *
 * <b>Guardrails (SPEC §5 change-state row):</b> jumps into OR out of a multi-instance
 * body are refused 422 (the loop's nrOfInstances/nrOfCompletedInstances bookkeeping
 * cannot survive foreign token moves); targets on an unjoined parallel branch produce a
 * sibling-token warning; a SUSPENDED instance is refused with activate-first guidance.
 *
 * <b>RBAC (ARCH §5):</b> tier 2 = OPERATOR floor; change-state is gated ADMIN on
 * {@code prod} engines. Restart-as-new stays OPERATOR (it creates a NEW instance and
 * never mutates the dead original).
 */
@Service
public class FlowSurgeryService {

    public static final String CHANGE_STATE_ACTION = "change-state";
    public static final String RESTART_ACTION = "restart-as-new";
    private static final int TIER = 2;

    /** Engine-populated intrinsics that must not be replayed onto a fresh instance. */
    private static final Set<String> INTRINSIC_VARIABLES = Set.of("initiator");

    /** Variable types that round-trip losslessly over the typed REST payload. */
    private static final Set<String> PORTABLE_VARIABLE_TYPES =
            Set.of("string", "integer", "long", "short", "double", "boolean", "date", "json", "uuid");

    private static final int VARIABLE_FETCH_CAP = 500;
    private static final int EXECUTION_FETCH_CAP = 200;

    private final EngineRegistry registry;
    private final FlowableEngineClient client;
    private final BpmnStructureService structures;
    private final AuditService audit;
    private final RbacAuthorizer rbac;
    private final ProtectedInstanceRepository protectedInstances;
    private final TicketPolicy ticketPolicy;

    public FlowSurgeryService(
            EngineRegistry registry,
            FlowableEngineClient client,
            BpmnStructureService structures,
            AuditService audit,
            RbacAuthorizer rbac,
            ProtectedInstanceRepository protectedInstances,
            TicketPolicy ticketPolicy) {
        this.registry = registry;
        this.client = client;
        this.structures = structures;
        this.audit = audit;
        this.rbac = rbac;
        this.protectedInstances = protectedInstances;
        this.ticketPolicy = ticketPolicy;
    }

    /* ------------------------------ change-state ------------------------------ */

    public ChangeStatePreview previewChangeState(
            String engineId, String instanceId, ChangeStateRequest request, Authentication auth) {
        EngineConfig engine = registry.require(engineId);
        ChangeStatePlan plan = planChangeState(engine, instanceId, request, auth);
        return new ChangeStatePreview(
                engineId,
                instanceId,
                plan.processDefinitionId(),
                "POST",
                "/runtime/process-instances/" + instanceId + "/change-state",
                plan.restBody(),
                plan.summary(),
                plan.warnings(),
                "BFF simulation — Flowable exposes no dry-run for change-state. Execute sends exactly this body;"
                        + " it re-validates against live state first.");
    }

    public ActionResult executeChangeState(
            String engineId, String instanceId, ChangeStateRequest request, Authentication auth) {
        EngineConfig engine = registry.require(engineId);
        // Server-fresh re-plan: every preview guard runs again against LIVE state.
        ChangeStatePlan plan = planChangeState(engine, instanceId, request, auth);
        requireUnprotectedOrAdmin(auth, engine, CHANGE_STATE_ACTION, instanceId);
        String reason = requireReason(request.reason(), CHANGE_STATE_ACTION);
        String ticketId = ticketPolicy.validate(request.ticketId(), engine.environment());

        AuditEntry entry = audit.beginPending(
                auth.getName(),
                engineId,
                blankToNull(engine.tenantId()),
                instanceId,
                CHANGE_STATE_ACTION,
                reason,
                ticketId,
                plan.auditPayload(),
                engine.auditPayloadOrDefault());

        dispatchAudited(entry, engineId, () -> {
            client.changeActivityState(engine, instanceId, plan.restBody());
            return null;
        });

        audit.close(entry, AuditOutcome.ok, 200, null, true);
        return new ActionResult(entry.getId(), entry.getCorrelationId(), "ok", 200, plan.deltaStatement());
    }

    /**
     * The shared pre-flight: all BLOCKING guards for preview and execute alike, so a
     * green preview honestly predicts an executable action. Preview-only leniency is
     * limited to what preview does not do (no reason, no protection check, no audit).
     */
    private ChangeStatePlan planChangeState(
            EngineConfig engine, String instanceId, ChangeStateRequest request, Authentication auth) {
        requireChangeStateRole(auth, engine);
        requireChangeStateCapability(engine);
        requireWritableEngine(engine, CHANGE_STATE_ACTION);

        List<String> sources = requiredIds(request.sourceActivityIds(), "sourceActivityIds");
        List<String> targets = requiredIds(request.targetActivityIds(), "targetActivityIds");

        Map<String, Object> instance = restateRunningInstance(engine, instanceId);
        String definitionId = String.valueOf(instance.get("processDefinitionId"));

        BpmnStructure model;
        Set<String> activeActivityIds;
        try {
            model = structures.structureOf(engine, definitionId);
            activeActivityIds = activeActivityIds(engine, instanceId);
        } catch (CallNotPermittedException | BulkheadFullException | ResourceAccessException e) {
            throw engineUnreachablePreFlight(engine);
        }

        List<BpmnStructure.FlowNode> sourceNodes = resolveNodes(model, sources);
        List<BpmnStructure.FlowNode> targetNodes = resolveNodes(model, targets);
        requireOutsideMultiInstance(model, sourceNodes, "source");
        requireOutsideMultiInstance(model, targetNodes, "target");
        requireSourcesActive(sources, activeActivityIds, instanceId);

        List<ChangeStatePreview.Warning> warnings = new ArrayList<>();
        for (BpmnStructure.FlowNode target : targetNodes) {
            if (model.insideParallelBranch(target.id())) {
                warnings.add(new ChangeStatePreview.Warning(
                        "parallel-branch-target",
                        "Target " + target.label() + " lies on a parallel-gateway branch: sibling branches keep"
                                + " (or will expect) their own tokens, and the join will wait for ALL of them."
                                + " Verify sibling tokens exist or move them in the same incident."));
            }
        }
        return new ChangeStatePlan(engine, instanceId, definitionId, sourceNodes, targetNodes, warnings);
    }

    private void requireChangeStateRole(Authentication auth, EngineConfig engine) {
        Role floor = engine.environment() == EngineEnvironment.PROD ? Role.ADMIN : Role.OPERATOR;
        if (!rbac.hasRoleOn(auth, floor, engine.id())) {
            throw new GuardRefusedException(
                    HttpStatus.FORBIDDEN,
                    "rbac-denied",
                    "'" + CHANGE_STATE_ACTION + "' (tier " + TIER + " flow surgery) requires " + floor + " on engine '"
                            + engine.id() + "'"
                            + (floor == Role.ADMIN ? " (ADMIN on prod engines, ARCH §5)." : "."));
        }
    }

    private void requireChangeStateCapability(EngineConfig engine) {
        EngineCapabilities capabilities = registry.healthOf(engine.id()).capabilities();
        if (capabilities == null) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "capability-unknown",
                    "Engine '" + engine.id() + "' has not answered a health probe yet — change-state support"
                            + " (Flowable ≥ 6.4) is unverified, so the call is refused rather than sent blind.");
        }
        if (!capabilities.changeState()) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "capability-unavailable",
                    "Engine '" + engine.id() + "' runs a Flowable version without the change-state endpoint"
                            + " (requires ≥ 6.4) — refused in the BFF, never a confusing engine 404.");
        }
    }

    private Map<String, Object> restateRunningInstance(EngineConfig engine, String instanceId) {
        Map<String, Object> instance;
        try {
            instance = client.getRuntimeProcessInstance(engine, instanceId);
        } catch (CallNotPermittedException | BulkheadFullException | ResourceAccessException e) {
            throw engineUnreachablePreFlight(engine);
        }
        if (instance == null) {
            throw new GuardRefusedException(
                    HttpStatus.NOT_FOUND,
                    "instance-not-running",
                    "Instance " + instanceId + " is not running on '" + engine.id()
                            + "' (completed or deleted?) — a token move needs live executions. Nothing happened.");
        }
        if (Boolean.TRUE.equals(instance.get("suspended"))) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "instance-suspended",
                    "Instance " + instanceId + " is SUSPENDED — activate it first (the 'activate' action), then"
                            + " retry the move. Nothing happened.");
        }
        return instance;
    }

    private Set<String> activeActivityIds(EngineConfig engine, String instanceId) {
        FlowablePage executions = client.listExecutions(engine, instanceId, EXECUTION_FETCH_CAP);
        Set<String> active = new HashSet<>();
        for (Map<String, Object> execution : executions.dataOrEmpty()) {
            Object activityId = execution.get("activityId");
            if (activityId != null) {
                active.add(String.valueOf(activityId));
            }
        }
        return active;
    }

    private static List<BpmnStructure.FlowNode> resolveNodes(BpmnStructure model, List<String> ids) {
        List<BpmnStructure.FlowNode> nodes = new ArrayList<>();
        for (String id : ids) {
            nodes.add(model.node(id)
                    .orElseThrow(() -> new GuardRefusedException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "unknown-activity",
                            "Activity '" + id + "' does not exist in this instance's deployed process model —"
                                    + " the move is unmappable. Nothing happened.")));
        }
        return nodes;
    }

    /** The MI block (both directions): SPEC §5 + v1.1 done-when ("an MI body as source is refused"). */
    private static void requireOutsideMultiInstance(
            BpmnStructure model, List<BpmnStructure.FlowNode> nodes, String side) {
        for (BpmnStructure.FlowNode node : nodes) {
            Optional<String> miRoot = model.multiInstanceScopeOf(node.id());
            if (miRoot.isPresent()) {
                throw new GuardRefusedException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "multi-instance-body",
                        "Refused: " + side + " activity " + node.label() + " is inside the multi-instance body"
                                + " rooted at '" + miRoot.get() + "'. Jumping a token into or out of an MI body"
                                + " corrupts the loop's instance bookkeeping"
                                + " (nrOfInstances/nrOfCompletedInstances). Nothing happened.");
            }
        }
    }

    private static void requireSourcesActive(List<String> sources, Set<String> active, String instanceId) {
        for (String source : sources) {
            if (!active.contains(source)) {
                throw new GuardRefusedException(
                        HttpStatus.CONFLICT,
                        "source-not-active",
                        "No execution of instance " + instanceId + " is currently at '" + source
                                + "' — live activities right now: " + (active.isEmpty() ? "none" : active)
                                + ". Re-check the instance; the state moved since your snapshot.");
            }
        }
    }

    /** Server-fresh restatement of ONE change-state plan — audit payload, REST body, operator copy. */
    private record ChangeStatePlan(
            EngineConfig engine,
            String instanceId,
            String processDefinitionId,
            List<BpmnStructure.FlowNode> sourceNodes,
            List<BpmnStructure.FlowNode> targetNodes,
            List<ChangeStatePreview.Warning> warnings) {

        Map<String, Object> restBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("cancelActivityIds", ids(sourceNodes));
            body.put("startActivityIds", ids(targetNodes));
            return body;
        }

        String summary() {
            return "Cancel the execution(s) at " + labels(sourceNodes) + " and start fresh one(s) at "
                    + labels(targetNodes) + " on " + engine.id() + ":" + instanceId
                    + ". History is append-only — the jump is recorded, never rewritten."
                    + (warnings.isEmpty() ? "" : " " + warnings.size() + " warning(s) attached.");
        }

        String deltaStatement() {
            return "Token moved on " + engine.id() + ":" + instanceId + " — canceled at " + labels(sourceNodes)
                    + ", started at " + labels(targetNodes)
                    + ". The move is recorded append-only; side effects of already-executed work are not undone.";
        }

        Map<String, Object> auditPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schema", CHANGE_STATE_ACTION + "/v1");
            payload.put("processDefinitionId", processDefinitionId);
            payload.put("sourceActivities", describe(sourceNodes));
            payload.put("targetActivities", describe(targetNodes));
            payload.put("endpoint", "POST /runtime/process-instances/" + instanceId + "/change-state");
            payload.put("restPayload", restBody());
            payload.put(
                    "warnings",
                    warnings.stream().map(ChangeStatePreview.Warning::code).toList());
            return payload;
        }

        private static List<String> ids(List<BpmnStructure.FlowNode> nodes) {
            return nodes.stream().map(BpmnStructure.FlowNode::id).toList();
        }

        private static String labels(List<BpmnStructure.FlowNode> nodes) {
            return String.join(
                    ", ", nodes.stream().map(BpmnStructure.FlowNode::label).toList());
        }

        private static List<Map<String, Object>> describe(List<BpmnStructure.FlowNode> nodes) {
            List<Map<String, Object>> described = new ArrayList<>();
            for (BpmnStructure.FlowNode node : nodes) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", node.id());
                m.put("name", node.name());
                m.put("type", node.type());
                described.add(m);
            }
            return described;
        }
    }

    /* ------------------------------ restart-as-new ------------------------------ */

    public RestartInstanceResult restartAsNew(
            String engineId, String instanceId, RestartInstanceRequest request, Authentication auth) {
        EngineConfig engine = registry.require(engineId);
        requireRole(auth, Role.OPERATOR, engine.id(), RESTART_ACTION);
        requireWritableEngine(engine, RESTART_ACTION);
        requireUnprotectedOrAdmin(auth, engine, RESTART_ACTION, instanceId);
        String reason = requireReason(request.reason(), RESTART_ACTION);
        String ticketId = ticketPolicy.validate(request.ticketId(), engine.environment());
        boolean pinVersion = Boolean.TRUE.equals(request.pinDefinitionVersion());

        Map<String, Object> historic;
        Map<String, Object> startBody;
        List<String> carried;
        Map<String, String> skipped;
        String startDefinitionRef;
        try {
            // Restart resurrects DEAD instances only — a live one must be terminated or moved.
            if (client.getRuntimeProcessInstance(engine, instanceId) != null) {
                throw new GuardRefusedException(
                        HttpStatus.CONFLICT,
                        "instance-still-running",
                        "Instance " + instanceId + " is still running on '" + engine.id()
                                + "' — restart-as-new applies to completed/terminated instances only"
                                + " (use change-state to move its token instead). Nothing happened.");
            }
            historic = client.getHistoricProcessInstance(engine, instanceId);
            if (historic == null) {
                throw new GuardRefusedException(
                        HttpStatus.NOT_FOUND,
                        "unknown-instance",
                        "Instance " + instanceId + " is unknown on '" + engine.id()
                                + "' — nothing to restart. Nothing happened.");
            }
            if (historic.get("endTime") == null) {
                throw new GuardRefusedException(
                        HttpStatus.CONFLICT,
                        "instance-not-ended",
                        "Instance " + instanceId + " has no end time yet — it is not dead. Nothing happened.");
            }

            String originalDefinitionId = String.valueOf(historic.get("processDefinitionId"));
            startDefinitionRef = resolveStartDefinition(engine, originalDefinitionId, historic, pinVersion);

            Map<String, String> skippedReasons = new LinkedHashMap<>();
            List<Map<String, Object>> variables = portableHistoricVariables(engine, instanceId, skippedReasons);
            skipped = skippedReasons;
            carried = variables.stream().map(v -> String.valueOf(v.get("name"))).toList();

            startBody = new LinkedHashMap<>();
            if (pinVersion) {
                startBody.put("processDefinitionId", startDefinitionRef);
            } else {
                startBody.put("processDefinitionKey", startDefinitionRef);
            }
            Object businessKey = historic.get("businessKey");
            if (businessKey != null) {
                startBody.put("businessKey", String.valueOf(businessKey));
            }
            if (!variables.isEmpty()) {
                startBody.put("variables", variables);
            }
        } catch (CallNotPermittedException | BulkheadFullException | ResourceAccessException e) {
            throw engineUnreachablePreFlight(engine);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", RESTART_ACTION + "/v1");
        payload.put("originalInstanceId", instanceId);
        payload.put("pinDefinitionVersion", pinVersion);
        payload.put(pinVersion ? "processDefinitionId" : "processDefinitionKey", startDefinitionRef);
        payload.put("businessKey", startBody.get("businessKey"));
        payload.put("carriedVariables", redactedVariableCopy(startBody));
        payload.put("skippedVariables", skipped);

        AuditEntry entry = audit.beginPending(
                auth.getName(),
                engineId,
                blankToNull(engine.tenantId()),
                instanceId,
                RESTART_ACTION,
                reason,
                ticketId,
                payload,
                engine.auditPayloadOrDefault());

        Map<String, Object> started =
                dispatchAudited(entry, engineId, () -> client.startProcessInstance(engine, startBody));

        String newInstanceId = String.valueOf(started.get("id"));
        String newDefinitionId = String.valueOf(started.get("processDefinitionId"));
        audit.close(entry, AuditOutcome.ok, 200, "{\"newProcessInstanceId\":\"" + newInstanceId + "\"}", true);

        String delta = "New instance " + newInstanceId + " started on definition " + newDefinitionId
                + (pinVersion ? " (version PINNED to the original)" : " (LATEST deployed version)") + " with "
                + carried.size() + " carried variable(s)"
                + (skipped.isEmpty() ? "" : ", " + skipped.size() + " skipped: " + skipped.keySet())
                + ". The original " + instanceId + " stays ended — history is never rewritten.";
        return new RestartInstanceResult(
                entry.getId(),
                entry.getCorrelationId(),
                "ok",
                200,
                newInstanceId,
                newDefinitionId,
                carried,
                skipped,
                delta);
    }

    /**
     * The explicit version fork: pinned → the original processDefinitionId (verified still
     * deployed); latest → the definition KEY. 6.x historic rows may omit
     * {@code processDefinitionKey}; the id's {@code key:version:uuid} shape is the
     * documented fallback (wire-shape memory, M2a).
     */
    private String resolveStartDefinition(
            EngineConfig engine, String originalDefinitionId, Map<String, Object> historic, boolean pinVersion) {
        if (pinVersion) {
            if (client.getProcessDefinition(engine, originalDefinitionId) == null) {
                throw new GuardRefusedException(
                        HttpStatus.NOT_FOUND,
                        "definition-gone",
                        "The original definition " + originalDefinitionId + " is no longer deployed on '"
                                + engine.id() + "' — retry with pinDefinitionVersion=false to use the latest"
                                + " version. Nothing happened.");
            }
            return originalDefinitionId;
        }
        Object key = historic.get("processDefinitionKey");
        String definitionKey =
                key != null ? String.valueOf(key) : originalDefinitionId.split(":", 2)[0];
        FlowablePage latest = client.listProcessDefinitionsByKey(engine, definitionKey, 1);
        if (latest == null || latest.total() == 0) {
            throw new GuardRefusedException(
                    HttpStatus.NOT_FOUND,
                    "definition-gone",
                    "No deployed version of definition key '" + definitionKey + "' exists on '" + engine.id()
                            + "' anymore. Nothing happened.");
        }
        return definitionKey;
    }

    /**
     * The historic variables worth replaying: process-scope (global) rows only — no
     * task-locals, no engine intrinsics, no types that cannot round-trip over the typed
     * REST payload. Everything intentionally dropped lands in {@code skipped} with its
     * reason; a silent drop would masquerade as a faithful copy.
     */
    private List<Map<String, Object>> portableHistoricVariables(
            EngineConfig engine, String instanceId, Map<String, String> skipped) {
        FlowablePage page = client.listHistoricVariableInstances(engine, instanceId, VARIABLE_FETCH_CAP);
        List<Map<String, Object>> rows = page != null ? page.dataOrEmpty() : List.of();
        if (page != null && page.total() > rows.size()) {
            throw new GuardRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "variable-set-too-large",
                    "Instance " + instanceId + " has " + page.total() + " historic variables — beyond the "
                            + VARIABLE_FETCH_CAP + "-variable restart cap. A partial copy would be a lie;"
                            + " nothing happened.");
        }
        List<Map<String, Object>> variables = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row.get("taskId") != null) {
                continue; // task-local — never instance state
            }
            Object wrapped = row.get("variable");
            if (!(wrapped instanceof Map<?, ?> variable)) {
                continue;
            }
            String name = String.valueOf(variable.get("name"));
            String type = variable.get("type") != null ? String.valueOf(variable.get("type")) : null;
            Object scope = variable.get("scope");
            if (scope != null && !"global".equals(String.valueOf(scope))) {
                continue; // execution-local (e.g. MI loop bookkeeping) — meaningless on a fresh instance
            }
            if (INTRINSIC_VARIABLES.contains(name)) {
                skipped.put(name, "engine-intrinsic — the engine sets this on start");
                continue;
            }
            if (type == null || !PORTABLE_VARIABLE_TYPES.contains(type)) {
                skipped.put(name, "type '" + type + "' does not round-trip over REST (no value in the wire form)");
                continue;
            }
            Map<String, Object> typed = new LinkedHashMap<>();
            typed.put("name", name);
            typed.put("type", type);
            typed.put("value", variable.get("value"));
            variables.add(typed);
        }
        return variables;
    }

    /** Audit copy of the carried variables — secret-NAMED values redacted (R-AUD-03). */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> redactedVariableCopy(Map<String, Object> startBody) {
        Object variables = startBody.get("variables");
        if (!(variables instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> variable = (Map<String, Object>) item;
            Map<String, Object> clean = new LinkedHashMap<>();
            String name = String.valueOf(variable.get("name"));
            clean.put("name", name);
            clean.put("type", variable.get("type"));
            clean.put("value", AuditService.isSecretName(name) ? AuditService.REDACTED : variable.get("value"));
            copy.add(clean);
        }
        return copy;
    }

    /* ------------------------- shared rails (M4 order) ------------------------- */

    private void requireRole(Authentication auth, Role floor, String engineId, String action) {
        if (!rbac.hasRoleOn(auth, floor, engineId)) {
            throw new GuardRefusedException(
                    HttpStatus.FORBIDDEN,
                    "rbac-denied",
                    "'" + action + "' (tier " + TIER + " flow surgery) requires " + floor + " on engine '" + engineId
                            + "'.");
        }
    }

    private void requireWritableEngine(EngineConfig engine, String action) {
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
                    "Engine '" + engine.id() + "' is registered read-only (R-GOV-04) — '" + action + "' is rejected.");
        }
    }

    private void requireUnprotectedOrAdmin(Authentication auth, EngineConfig engine, String action, String targetId) {
        Optional<ProtectedInstance> protection;
        try {
            protection = protectedInstances.findById(new ProtectedInstance.Key(engine.id(), targetId));
        } catch (RuntimeException e) {
            // Protection registry and audit share one Postgres: unreadable = fail-closed (R-AUD-01).
            throw new AuditUnavailableException(e);
        }
        if (protection.isPresent() && !rbac.hasRoleOn(auth, Role.ADMIN, engine.id())) {
            throw new GuardRefusedException(
                    HttpStatus.FORBIDDEN,
                    "instance-protected",
                    "Instance " + engine.id() + ":" + targetId + " is protected (R-SAFE-05): \""
                            + protection.get().getReason() + "\" — L3 (ADMIN) action required for '" + action + "'.");
        }
    }

    /** Tier-2 reason discipline (SPEC §6): ALWAYS required, ≥10 chars, every environment. */
    private static String requireReason(String reason, String action) {
        String normalized = blankToNull(reason);
        if (normalized == null) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "reason-required",
                    "'" + action + "' (tier " + TIER + " flow surgery) always requires a reason of at least 10"
                            + " characters.");
        }
        if (normalized.length() < 10) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST, "reason-too-short", "The reason must be at least 10 characters.");
        }
        return normalized;
    }

    /** The ONE engine call with the M4 outcome discipline — never retried, honest close-out. */
    private <T> T dispatchAudited(AuditEntry entry, String engineId, Supplier<T> call) {
        // Forward the acting human (== this audit row's actor) on the dispatching thread, so an
        // identity-forwarding engine sees it (M4-CLOSEOUT §2 / D2a). Cleared in finally.
        ForwardedActor.set(entry.forwardedIdentity());
        try {
            return call.get();
        } catch (CallNotPermittedException | BulkheadFullException e) {
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
        } finally {
            ForwardedActor.clear();
        }
    }

    private static GuardRefusedException engineUnreachablePreFlight(EngineConfig engine) {
        return new GuardRefusedException(
                HttpStatus.BAD_GATEWAY,
                "engine-unreachable",
                "Engine '" + engine.id() + "' did not answer the pre-flight read — the action was not sent.");
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

    private static List<String> requiredIds(List<String> ids, String field) {
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "missing-field",
                    "change-state requires a non-empty '" + field + "' list of activity ids.");
        }
        return ids;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
