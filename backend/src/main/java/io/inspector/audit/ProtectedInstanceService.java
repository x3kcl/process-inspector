package io.inspector.audit;

import io.inspector.action.GuardRefusedException;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * R-SAFE-05 write path: mark/unmark a composite ID protected. Instance-scoped only for v1 —
 * the spec text also mentions definition-key-level protection, but the schema
 * ({@code protected_instance}: {@code engine_id}+{@code instance_id} PK) never carried that
 * scope, and this change does not add it (tracked as a separate follow-up rather than
 * expanding this write path's blast radius).
 *
 * <p>Same shape as {@code ErrorGroupAckService}/{@code SharedViewService.unpublish} — a
 * BFF-store-only mutation under the corrective-actions rails minus the engine call (there is
 * none): the controller door is the ADMIN-per-engine floor
 * ({@code @rbac.atLeastOn(authentication, 'ADMIN', #engineId)}), this service re-checks it,
 * reason is required either way (mark AND unmark are both "tier-3, audited" per R-SAFE-05),
 * and the store write is fail-closed audited (write → audit → compensate on
 * {@link AuditUnavailableException}).
 *
 * <p>Deliberately NOT behind {@code DangerousActionReauthGate} (R-SAFE-07): that gate's
 * documented sweep is tier-3 engine-mutating {@code ActionVerb}s, bulk submission, and
 * access-mapping writes (IDP-SECURITY §5) — a closed list scoped to actions that either reach
 * a live engine or rewrite who-can-do-what. This mutation is neither: a locally reversible
 * governance flag, already RBAC+reason+audit-gated, matching how its two closest siblings
 * ({@code ErrorGroupAckService}, {@code SharedViewService.unpublish}) are not reauth-gated
 * either.
 */
@Service
public class ProtectedInstanceService {

    static final String ACTION_PROTECT = "instance-protect";
    static final String ACTION_UNPROTECT = "instance-unprotect";
    private static final int MIN_REASON_LENGTH = 10;

    private final ProtectedInstanceRepository repository;
    private final AuditService audit;
    private final RbacAuthorizer rbac;
    private final Clock clock;

    public ProtectedInstanceService(
            ProtectedInstanceRepository repository, AuditService audit, RbacAuthorizer rbac, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.rbac = rbac;
        this.clock = clock;
    }

    public void protect(Authentication auth, String engineId, String instanceId, String reason) {
        String clean = requireReason(reason);
        requireAdminOn(auth, engineId);

        ProtectedInstance.Key key = new ProtectedInstance.Key(engineId, instanceId);
        if (repository.findById(key).isPresent()) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "already-protected",
                    "Instance " + engineId + ":" + instanceId
                            + " is already protected — unprotect it first to change the reason.");
        }

        Instant now = clock.instant();
        ProtectedInstance saved;
        try {
            saved = repository.saveAndFlush(new ProtectedInstance(engineId, instanceId, clean, auth.getName(), now));
        } catch (DataIntegrityViolationException race) {
            // A concurrent protect won the unique (engine_id, instance_id) PK — a clean 409,
            // never a bare 500 (SharedViewService.publish precedent for the same race shape).
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "already-protected",
                    "Instance " + engineId + ":" + instanceId
                            + " is already protected — unprotect it first to change the reason.");
        }

        auditOrCompensate(ACTION_PROTECT, auth.getName(), clean, payload(engineId, instanceId, clean), () -> {
            repository.delete(saved);
            repository.flush();
        });
    }

    public void unprotect(Authentication auth, String engineId, String instanceId, String reason) {
        String clean = requireReason(reason);
        requireAdminOn(auth, engineId);

        ProtectedInstance.Key key = new ProtectedInstance.Key(engineId, instanceId);
        Optional<ProtectedInstance> existing = repository.findById(key);
        if (existing.isEmpty()) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "not-protected",
                    "Instance " + engineId + ":" + instanceId + " is not protected — refresh and try again.");
        }
        ProtectedInstance detached = existing.get();

        repository.delete(detached);
        repository.flush();

        auditOrCompensate(ACTION_UNPROTECT, auth.getName(), clean, payload(engineId, instanceId, clean), () -> {
            repository.saveAndFlush(new ProtectedInstance(
                    detached.getEngineId(),
                    detached.getInstanceId(),
                    detached.getReason(),
                    detached.getCreatedBy(),
                    detached.getTs()));
        });
    }

    private void requireAdminOn(Authentication auth, String engineId) {
        // The controller door is the ADMIN-per-engine floor; this is the service-layer
        // re-check (rails §2 — never trust the client, and @PreAuthorize alone can drift
        // from a SpEL typo without a compile-time check).
        if (!rbac.hasRoleOn(auth, Role.ADMIN, engineId)) {
            throw new GuardRefusedException(
                    HttpStatus.FORBIDDEN,
                    "rbac-denied",
                    "Marking or unmarking protection needs ADMIN on '" + engineId + "'. Nothing happened.");
        }
    }

    private static String requireReason(String reason) {
        String clean = reason == null ? "" : reason.strip();
        if (clean.length() < MIN_REASON_LENGTH) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "reason-too-short",
                    "The reason must be at least " + MIN_REASON_LENGTH + " characters.");
        }
        return clean;
    }

    private static Map<String, Object> payload(String engineId, String instanceId, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("engineId", engineId);
        payload.put("instanceId", instanceId);
        payload.put("reason", reason);
        return payload;
    }

    /** Config-event audit, fail-closed (R-AUD-10): on failure, undo the store change and refuse 503. */
    private void auditOrCompensate(
            String action, String actor, String reason, Map<String, Object> payload, Runnable compensate) {
        try {
            audit.recordConfigEvent(action, actor, true, reason, payload);
        } catch (AuditUnavailableException e) {
            compensate.run();
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Refused fail-closed: the change was NOT applied because the audit store is unavailable",
                    e);
        }
    }
}
