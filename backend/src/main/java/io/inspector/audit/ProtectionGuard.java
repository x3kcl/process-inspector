package io.inspector.audit;

import io.inspector.action.GuardRefusedException;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * R-SAFE-05 read-side enforcement, shared by every verb-executing service ({@code
 * CorrectiveActionService}, {@code FlowSurgeryService}, {@code MigrationService}) — previously
 * three verbatim-duplicated private methods (#172 review of #165's follow-up list flagged this
 * explicitly). Checks BOTH scopes: a specific composite ID ({@code protected_instance}, #165) and
 * a whole process-definition key ({@code protected_definition}, #172) — either one refuses below
 * the ADMIN floor. Pass {@code null} for whichever scope isn't being checked at a given call site
 * (most call sites only have one of the two IDs cheaply available — see the class javadocs on the
 * three callers for exactly which).
 */
@Component
public class ProtectionGuard {

    private final ProtectedInstanceRepository instances;
    private final ProtectedDefinitionRepository definitions;
    private final RbacAuthorizer rbac;

    public ProtectionGuard(
            ProtectedInstanceRepository instances, ProtectedDefinitionRepository definitions, RbacAuthorizer rbac) {
        this.instances = instances;
        this.definitions = definitions;
        this.rbac = rbac;
    }

    /**
     * @param instanceId checked against {@code protected_instance} when non-null
     * @param definitionKey checked against {@code protected_definition} when non-null
     * @param actionLabel named in the refusal message (e.g. a verb path or a fixed action constant)
     */
    public void requireUnprotectedOrAdmin(
            Authentication auth, String engineId, String instanceId, String definitionKey, String actionLabel) {
        if (instanceId != null) {
            Optional<ProtectedInstance> protection;
            try {
                protection = instances.findById(new ProtectedInstance.Key(engineId, instanceId));
            } catch (RuntimeException e) {
                // The protection registry lives in the same Postgres as the audit log: if we
                // cannot check it, we cannot audit either — same fail-closed refusal (R-AUD-01).
                throw new AuditUnavailableException(e);
            }
            if (protection.isPresent() && !rbac.hasRoleOn(auth, Role.ADMIN, engineId)) {
                throw new GuardRefusedException(
                        HttpStatus.FORBIDDEN,
                        "instance-protected",
                        "Instance " + engineId + ":" + instanceId + " is protected (R-SAFE-05): \""
                                + protection.get().getReason() + "\" — L3 (ADMIN) action required for '" + actionLabel
                                + "'.");
            }
        }
        if (definitionKey != null) {
            Optional<ProtectedDefinition> protection;
            try {
                protection = definitions.findById(new ProtectedDefinition.Key(engineId, definitionKey));
            } catch (RuntimeException e) {
                throw new AuditUnavailableException(e);
            }
            if (protection.isPresent() && !rbac.hasRoleOn(auth, Role.ADMIN, engineId)) {
                throw new GuardRefusedException(
                        HttpStatus.FORBIDDEN,
                        "definition-protected",
                        "Definition '" + definitionKey + "' on " + engineId + " is protected (R-SAFE-05): \""
                                + protection.get().getReason() + "\" — L3 (ADMIN) action required for '" + actionLabel
                                + "'.");
            }
        }
    }
}
