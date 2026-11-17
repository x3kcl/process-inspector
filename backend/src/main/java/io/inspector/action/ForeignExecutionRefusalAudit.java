package io.inspector.action;

import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditOutcome;
import io.inspector.audit.AuditService;
import io.inspector.audit.AuditUnavailableException;
import io.inspector.audit.BreakGlassActor;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.security.RbacAuthorizer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;

/**
 * #773: the one pre-flight refusal of a mutating verb that is audited — an {@code executionId}
 * that belongs to ANOTHER instance ({@code execution-instance-mismatch}, #759), the probe shape of
 * a guard bypass. A {@code failed} row: actor, engine, the path instance, the verb, the foreign
 * execution id, the reason code — never a variable value. Bad input and capability refusals stay
 * unaudited so the trail does not fill with typos.
 *
 * <p>If the row cannot be written the refusal is still returned: nothing was sent to the engine,
 * so the fail-closed rule (no unaudited MUTATION) is not at stake, and the caller must learn why.
 */
final class ForeignExecutionRefusalAudit {

    static final String CODE = "execution-instance-mismatch";

    private static final Logger LOG = LoggerFactory.getLogger(ForeignExecutionRefusalAudit.class);

    private ForeignExecutionRefusalAudit() {}

    static void record(
            AuditService audit,
            RbacAuthorizer rbac,
            Authentication auth,
            EngineConfig engine,
            String instanceId,
            ActionVerb verb,
            String reason,
            String executionId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", CODE + "/v1");
        payload.put("executionId", executionId);
        BreakGlassActor.set(rbac.isBreakGlass(auth));
        try {
            AuditEntry entry = audit.beginPending(
                    auth.getName(),
                    engine.id(),
                    engine.tenantId() == null || engine.tenantId().isBlank() ? null : engine.tenantId(),
                    instanceId,
                    verb.path(),
                    reason,
                    null,
                    payload,
                    engine.auditPayloadOrDefault());
            audit.close(entry, AuditOutcome.failed, 400, CODE, false);
        } catch (AuditUnavailableException e) {
            LOG.warn("{} refusal of {} on {}:{} could not be audited", CODE, verb.path(), engine.id(), instanceId, e);
        } finally {
            BreakGlassActor.clear();
        }
    }
}
