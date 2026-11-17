package io.inspector.detail;

import io.inspector.action.ActionScope;
import io.inspector.action.CorrectiveActionService;
import io.inspector.action.GuardRefusedException;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditOutcome;
import io.inspector.audit.AuditService;
import io.inspector.audit.BreakGlassActor;
import io.inspector.audit.SecretNames;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.ExecutionRecord;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.VariableRecord;
import io.inspector.cmmn.CmmnCapabilities;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.RevealProperties;
import io.inspector.dto.RevealedVariable;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.ReadScopeGate;
import io.inspector.security.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The audited reveal of ONE secret-named variable value (#743, SPEC R-AUD-12). Not a corrective verb —
 * it mutates nothing and must RETURN the value, which {@code CorrectiveActionService}'s
 * {@code ActionResult} never carries — but it takes the rails that apply, in this order:
 *
 * <ol>
 *   <li>per-user rate limit ({@code inspector.reveal.max-per-minute}); the breach audited once per window;
 *   <li><b>audit INSERT (PENDING) — fail-closed</b>: no row, no value. Every later refusal closes it
 *       {@code failed} with its status, so a refused attempt is on record;
 *   <li>read scope (under enforcement), then the tier: OPERATOR and above on the engine;
 *   <li>secret-named only (else 400); the reason ladder of a tier-1 verb (required ≥10 chars on prod);
 *   <li>ONE engine read, never retried; close {@code ok} — a failed close withholds the value.
 * </ol>
 *
 * The value is never logged, never in the audit row, never in an error body.
 */
@Service
public class VariableRevealService {

    public static final String ACTION = "reveal-variable";
    private static final int TIER = 1;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final EngineRegistry registry;
    private final InstanceDetailService detail;
    private final CmmnApiClient cmmn;
    private final ProcessApiClient flowable;
    private final AuditService audit;
    private final RbacAuthorizer rbac;
    private final ReadScopeGate readScope;
    private final RevealProperties properties;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public VariableRevealService(
            EngineRegistry registry,
            InstanceDetailService detail,
            CmmnApiClient cmmn,
            ProcessApiClient flowable,
            AuditService audit,
            RbacAuthorizer rbac,
            ReadScopeGate readScope,
            RevealProperties properties,
            Clock clock) {
        this.registry = registry;
        this.detail = detail;
        this.cmmn = cmmn;
        this.flowable = flowable;
        this.audit = audit;
        this.rbac = rbac;
        this.readScope = readScope;
        this.properties = properties;
        this.clock = clock;
    }

    public RevealedVariable reveal(
            ActionScope scope,
            String engineId,
            String targetId,
            String name,
            String executionId,
            String reason,
            Authentication auth) {
        EngineConfig engine = registry.require(engineId);
        boolean local = scope == ActionScope.BPMN && executionId != null && !executionId.isBlank();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("scope", local ? "local" : "global");
        if (local) {
            payload.put("executionId", executionId);
        }
        payload.put("scopeType", scope == ActionScope.CMMN ? "cmmn" : "bpmn");
        String givenReason = reason == null || reason.isBlank() ? null : reason;

        Admission admission = admit(auth.getName());
        if (admission != Admission.ADMIT) {
            int limit = properties.maxPerMinuteOrDefault();
            if (admission == Admission.FIRST_BREACH) {
                AuditEntry entry = begin(auth, engine, targetId, givenReason, payload);
                audit.close(entry, AuditOutcome.failed, 429, "rate limit: " + limit + " reveals per minute", false);
            }
            throw new GuardRefusedException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "reveal-rate-limited",
                    "At most " + limit + " reveals per minute per user — wait a minute. Nothing was revealed.");
        }

        AuditEntry entry = begin(auth, engine, targetId, givenReason, payload);
        VariableRecord row;
        try {
            if (!readScope.canRead(auth, engineId)) {
                throw new GuardRefusedException(
                        HttpStatus.FORBIDDEN, "rbac-denied", "Engine '" + engineId + "' is outside your read scope.");
            }
            if (!rbac.hasRoleOn(auth, Role.OPERATOR, engineId)) {
                throw new GuardRefusedException(
                        HttpStatus.FORBIDDEN,
                        "rbac-denied",
                        "'" + ACTION + "' (tier " + TIER + ") requires OPERATOR on engine '" + engineId + "'.");
            }
            if (!SecretNames.isSecret(name)) {
                throw new GuardRefusedException(
                        HttpStatus.BAD_REQUEST,
                        "variable-not-secret",
                        "'" + name + "' is not secret-named — its value is shown unmasked; reveal is only for"
                                + " masked variables.");
            }
            CorrectiveActionService.normalizedReason(engine, TIER, ACTION, reason);
            row = read(scope, engine, targetId, name, executionId);
        } catch (GuardRefusedException e) {
            audit.close(entry, AuditOutcome.failed, e.status().value(), e.code(), false);
            throw e;
        } catch (ResponseStatusException e) {
            audit.close(
                    entry, AuditOutcome.failed, e.getStatusCode().value(), "not found / refused by the read", false);
            throw e;
        } catch (RuntimeException e) {
            audit.close(
                    entry,
                    AuditOutcome.failed,
                    null,
                    "engine read failed: " + e.getClass().getSimpleName(),
                    false);
            throw e;
        }
        // Fail-closed close-out: a close that cannot be recorded throws, and the value never leaves.
        audit.close(entry, AuditOutcome.ok, 200, null, true);
        return new RevealedVariable(
                row.name(),
                row.type(),
                row.value(),
                local ? "local" : "global",
                local ? executionId : null,
                entry.getId());
    }

    private AuditEntry begin(
            Authentication auth, EngineConfig engine, String targetId, String reason, Map<String, Object> payload) {
        BreakGlassActor.set(rbac.isBreakGlass(auth));
        try {
            return audit.beginPending(
                    auth.getName(),
                    engine.id(),
                    engine.tenantId() == null || engine.tenantId().isBlank() ? null : engine.tenantId(),
                    targetId,
                    ACTION,
                    reason,
                    null,
                    payload,
                    engine.auditPayloadOrDefault());
        } finally {
            BreakGlassActor.clear();
        }
    }

    private VariableRecord read(
            ActionScope scope, EngineConfig engine, String targetId, String name, String executionId) {
        if (scope == ActionScope.BPMN) {
            if (executionId != null && !executionId.isBlank()) {
                // The step-local edit's check: an execution of ANOTHER instance would reveal its value
                // under this instance's audit row. A gone execution falls through to the read's 404.
                ExecutionRecord execution = flowable.getExecution(engine, CallPriority.INTERACTIVE, executionId);
                String owner = execution == null ? null : execution.processInstanceId();
                if (owner != null && !owner.equals(targetId)) {
                    throw new GuardRefusedException(
                            HttpStatus.BAD_REQUEST,
                            "execution-instance-mismatch",
                            "Execution " + executionId + " belongs to another instance, not " + targetId + ".");
                }
            }
            return detail.rawVariable(engine, targetId, name, executionId);
        }
        CmmnCapabilities.requireScopeType(registry, engine);
        VariableRecord row = cmmn.getCaseVariable(engine, CallPriority.INTERACTIVE, targetId, name);
        if (row == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "variable '" + name + "' not found on case " + engine.id() + ":" + targetId);
        }
        return row;
    }

    /* ---------------- per-user rate limit ---------------- */

    private enum Admission {
        ADMIT,
        FIRST_BREACH,
        REPEAT_BREACH
    }

    private record Window(Instant start, int count, Admission last) {}

    /** A 60-second window per user from its first call; the call past the limit breaches once, then repeats. */
    private Admission admit(String actor) {
        Instant now = clock.instant();
        int limit = properties.maxPerMinuteOrDefault();
        return windows.compute(actor, (key, old) -> {
                    if (old == null || !now.isBefore(old.start().plus(WINDOW))) {
                        return new Window(now, 1, Admission.ADMIT);
                    }
                    int count = old.count() + 1;
                    Admission admission = count <= limit
                            ? Admission.ADMIT
                            : old.last() == Admission.ADMIT ? Admission.FIRST_BREACH : Admission.REPEAT_BREACH;
                    return new Window(old.start(), count, admission);
                })
                .last();
    }
}
