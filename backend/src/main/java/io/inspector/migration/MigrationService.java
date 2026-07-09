package io.inspector.migration;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.inspector.action.ActionResult;
import io.inspector.action.EngineRejectedException;
import io.inspector.action.GuardRefusedException;
import io.inspector.action.OutcomeUnknownException;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditOutcome;
import io.inspector.audit.AuditService;
import io.inspector.audit.AuditUnavailableException;
import io.inspector.audit.ProtectedInstance;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.FlowableEngineClient;
import io.inspector.client.FlowableEngineClient.FlowablePage;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import io.inspector.surgery.BpmnStructure;
import io.inspector.surgery.BpmnStructureService;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Instance migration (SPEC §5 tier-3 "Migrate instance") — move a running instance forward to
 * another deployed version of the SAME process key, without terminate+restart. Clones the
 * {@link io.inspector.surgery.FlowSurgeryService} rail order (RBAC → capability → writable →
 * server-fresh restatement → target resolution → protection → reason → typed confirm → fail-closed
 * audit → ONE engine call, never retried → honest close-out) and its BFF-simulation honesty.
 *
 * <p><b>P0 spike (2026-07-09):</b> Flowable's REST API exposes NO migration validator on any
 * version — only the execute route {@code POST /runtime/process-instances/{id}/migrate}
 * (document field {@code activityMappings}). So the "preview" is a BFF static auto-map check
 * ({@link MigrationDiff}), an explicit Inspector estimate — never an engine validation. Execute
 * is the one real engine call; the engine's verbatim apply-time rejection is the ground truth.
 */
@Service
public class MigrationService {

    public static final String MIGRATE_ACTION = "migrate-instance";
    private static final int TIER = 3;
    private static final int CHILD_COUNT_CAP_PROBE = 1;
    private static final int EXECUTION_FETCH_CAP = 200;

    private final EngineRegistry registry;
    private final FlowableEngineClient client;
    private final BpmnStructureService structures;
    private final RbacAuthorizer rbac;
    private final AuditService audit;
    private final ProtectedInstanceRepository protectedInstances;

    public MigrationService(
            EngineRegistry registry,
            FlowableEngineClient client,
            BpmnStructureService structures,
            RbacAuthorizer rbac,
            AuditService audit,
            ProtectedInstanceRepository protectedInstances) {
        this.registry = registry;
        this.client = client;
        this.structures = structures;
        this.rbac = rbac;
        this.audit = audit;
        this.protectedInstances = protectedInstances;
    }

    /* --------------------------------- preview (S1) --------------------------------- */

    /**
     * The static pre-check: run every BLOCKING guard against live state, resolve+pin the target,
     * classify the instance's active activities against the target model. Read-only — no audit row
     * (mirrors {@code previewChangeState}). Execute re-plans server-fresh and never trusts a
     * previously issued preview.
     */
    public MigrationPreview preview(String engineId, String instanceId, MigrationRequest request, Authentication auth) {
        MigrationPlan plan = plan(engineId, instanceId, request, auth);
        return new MigrationPreview(
                engineId,
                instanceId,
                plan.fromDefinitionId(),
                plan.fromKey(),
                plan.fromVersion(),
                plan.target().definitionId(),
                plan.target().version(),
                false,
                plan.executable(),
                plan.activities(),
                plan.activityStateDigest(),
                plan.childCount(),
                "POST",
                "/runtime/process-instances/" + instanceId + "/migrate",
                plan.restBody(),
                plan.summary(),
                MigrationPreview.BANNER);
    }

    /* --------------------------------- execute (S2) --------------------------------- */

    /**
     * The one real engine call, through the full tier-3 rails. Re-plans server-fresh, asserts the
     * §5 compare-and-set (the instance has not moved since the operator previewed), then protection
     * → reason → prod typed-confirm → fail-closed audit → ONE {@code migrate} POST, never retried.
     * The engine either applies it (200 — we re-read and record the observed definition to prove
     * the move landed) or rejects the WHOLE document atomically with a verbatim message that closes
     * the audit as {@code failed}; a post-dispatch timeout is {@code unknown}, never re-fired.
     */
    public ActionResult execute(String engineId, String instanceId, MigrationRequest request, Authentication auth) {
        MigrationPlan plan = plan(engineId, instanceId, request, auth);
        EngineConfig engine = plan.engine();

        assertNotMovedSincePreview(plan, request);
        requireUnprotectedOrAdmin(auth, engine, instanceId);
        String reason = requireReason(request.reason());
        requireConfirmToken(engine, plan, request.confirmToken());
        requireExecutable(plan);

        AuditEntry entry = audit.beginPending(
                auth.getName(),
                engineId,
                blankToNull(engine.tenantId()),
                instanceId,
                MIGRATE_ACTION,
                reason,
                blankToNull(request.ticketId()),
                plan.auditPayload());

        dispatchAudited(entry, engineId, () -> {
            client.migrateInstance(engine, instanceId, plan.restBody());
            return null;
        });

        String observedDefinitionId = observedDefinitionId(engine, instanceId);
        audit.close(
                entry,
                AuditOutcome.ok,
                200,
                "{\"observedProcessDefinitionId\":\"" + observedDefinitionId + "\"}",
                true);
        return new ActionResult(
                entry.getId(), entry.getCorrelationId(), "ok", 200, plan.deltaStatement(observedDefinitionId));
    }

    /* ------------------------- the shared server-fresh plan ------------------------- */

    private MigrationPlan plan(String engineId, String instanceId, MigrationRequest request, Authentication auth) {
        EngineConfig engine = registry.require(engineId);
        requireMigrateRole(auth, engine);
        requireMigrationCapability(engine);
        requireWritableEngine(engine);

        Map<String, Object> instance = restateRunningInstance(engine, instanceId);
        String fromDefinitionId = String.valueOf(instance.get("processDefinitionId"));
        String fromKey = definitionKeyOf(fromDefinitionId);
        int fromVersion = definitionVersionOf(fromDefinitionId);
        String fromTenant = asString(instance.get("tenantId"));
        String businessKey = asString(instance.get("businessKey"));

        ResolvedTarget target = resolveTarget(engine, request, fromKey, fromVersion, fromTenant);
        List<MigrationMapping> overrides = validatedOverrides(request.mappingsOrEmpty());

        List<ActivityDiffEntry> activities;
        List<String> activeActivityIds;
        int childCount;
        try {
            BpmnStructure sourceModel = structures.structureOf(engine, fromDefinitionId);
            BpmnStructure targetModel = structures.structureOf(engine, target.definitionId());
            activeActivityIds = activeActivityIds(engine, instanceId);
            activities = MigrationDiff.diff(
                    MigrationDiff.of(sourceModel), MigrationDiff.of(targetModel), activeActivityIds, overrides);
            childCount = callActivityChildCount(engine, instanceId);
        } catch (CallNotPermittedException | BulkheadFullException | ResourceAccessException e) {
            throw engineUnreachablePreFlight(engine);
        }

        return new MigrationPlan(
                engine,
                instanceId,
                fromDefinitionId,
                fromKey,
                fromVersion,
                businessKey,
                target,
                overrides,
                activities,
                ActivityStateDigest.of(activeActivityIds),
                childCount);
    }

    /**
     * Decision P0-4: the compare-and-set that binds preview to execute. BOTH assertions are
     * MANDATORY — execute must carry the {@code fromDefinitionId} and {@code activityStateDigest}
     * the preview returned (the frontend echoes them back), so an operator can never migrate under
     * a stale approval. Both are needed: the digest catches a token move; the definition id catches
     * a same-token-set migration to a different version between preview and execute (the digest
     * alone would miss it). Refuse 400 if either is absent (no blind execute), 409 if either
     * diverges (the instance moved).
     */
    private void assertNotMovedSincePreview(MigrationPlan plan, MigrationRequest request) {
        if (blankToNull(request.expectedFromDefinitionId()) == null
                || blankToNull(request.expectedActivityStateDigest()) == null) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "preview-required",
                    "Migration execute must carry the fromDefinitionId and activityStateDigest from a fresh preview"
                            + " (the compare-and-set that proves the instance hasn't moved). Preview first. Nothing"
                            + " happened.");
        }
        if (!request.expectedFromDefinitionId().equals(plan.fromDefinitionId())) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "instance-moved-since-preview",
                    "Instance " + plan.instanceId() + " is now on definition " + plan.fromDefinitionId()
                            + ", not the " + request.expectedFromDefinitionId() + " you previewed — re-preview and"
                            + " approve the current state. Nothing happened.");
        }
        if (!request.expectedActivityStateDigest().equals(plan.activityStateDigest())) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "instance-moved-since-preview",
                    "Instance " + plan.instanceId() + " token position changed since you previewed — re-preview and"
                            + " approve the current state. Nothing happened.");
        }
    }

    private void requireExecutable(MigrationPlan plan) {
        List<String> flagged = plan.activities().stream()
                .filter(ActivityDiffEntry::isBlocker)
                .map(ActivityDiffEntry::fromActivityId)
                .toList();
        if (!flagged.isEmpty()) {
            throw new GuardRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "unmapped-activities",
                    "Cannot migrate: " + flagged + " have no target mapping — the engine would reject the whole"
                            + " document. Supply a mapping for each and re-preview. Nothing happened.");
        }
    }

    /* ------------------------------- target resolution ------------------------------- */

    private record ResolvedTarget(String definitionId, int version) {}

    /**
     * Resolve the operator's target selector to a concrete, PINNED {@code toProcessDefinitionId}:
     * an explicit id wins; else a specific version of the instance's key; else the latest. Same
     * key required (cross-key → 422), deployed, different version (same version → 409 no-op), same
     * tenant (cross-tenant → 422). All slice-1 scope boundaries.
     */
    private ResolvedTarget resolveTarget(
            EngineConfig engine, MigrationRequest request, String fromKey, int fromVersion, String fromTenant) {
        Map<String, Object> targetDef;
        try {
            if (request.toDefinitionId() != null && !request.toDefinitionId().isBlank()) {
                targetDef = client.getProcessDefinition(engine, request.toDefinitionId());
            } else if (request.toVersion() != null) {
                targetDef = firstOrNull(client.listProcessDefinitionsByKey(engine, fromKey, request.toVersion(), 1));
            } else {
                // Default target = the LATEST version (latest=true; a plain size=1 does NOT sort by version).
                targetDef = firstOrNull(client.latestProcessDefinitionByKey(engine, fromKey));
            }
        } catch (CallNotPermittedException | BulkheadFullException | ResourceAccessException e) {
            throw engineUnreachablePreFlight(engine);
        }
        if (targetDef == null) {
            throw new GuardRefusedException(
                    HttpStatus.NOT_FOUND,
                    "target-not-deployed",
                    "No matching target version of '" + fromKey + "' is deployed on '" + engine.id()
                            + "'. Nothing happened.");
        }
        String targetKey = asString(targetDef.get("key"));
        if (!fromKey.equals(targetKey)) {
            throw new GuardRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "cross-key-migration",
                    "Target definition '" + targetKey + "' has a different process key than the instance ('" + fromKey
                            + "') — cross-key migration is out of scope. Nothing happened.");
        }
        String targetTenant = asString(targetDef.get("tenantId"));
        if (!tenantsMatch(fromTenant, targetTenant)) {
            throw new GuardRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "cross-tenant-migration",
                    "Target definition is in tenant '" + targetTenant + "' but the instance is in '" + fromTenant
                            + "' — cross-tenant migration is out of scope. Nothing happened.");
        }
        int targetVersion = ((Number) targetDef.get("version")).intValue();
        if (targetVersion == fromVersion) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "same-version-target",
                    "The instance already runs version " + fromVersion + " of '" + fromKey
                            + "' — migrating to the same version is a no-op. Nothing happened.");
        }
        return new ResolvedTarget(asString(targetDef.get("id")), targetVersion);
    }

    /* --------------------------------- guards (cloned) --------------------------------- */

    private void requireMigrateRole(Authentication auth, EngineConfig engine) {
        // Tier-3: ADMIN floor, UNCONDITIONAL every environment (typed-confirm is the prod
        // escalation, not a role change). Decision P0/Q7.
        if (!rbac.hasRoleOn(auth, Role.ADMIN, engine.id())) {
            throw new GuardRefusedException(
                    HttpStatus.FORBIDDEN,
                    "rbac-denied",
                    "'" + MIGRATE_ACTION + "' (tier " + TIER + ") requires ADMIN on engine '" + engine.id()
                            + "' (every environment).");
        }
    }

    private void requireMigrationCapability(EngineConfig engine) {
        EngineCapabilities capabilities = registry.healthOf(engine.id()).capabilities();
        if (capabilities == null) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "capability-unknown",
                    "Engine '" + engine.id() + "' has not answered a health probe yet — migration support"
                            + " (Flowable ≥ 6.5) is unverified, so the call is refused rather than sent blind.");
        }
        if (!capabilities.migration()) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "capability-unavailable",
                    "Engine '" + engine.id() + "' runs a Flowable version without process migration"
                            + " (requires ≥ 6.5) — refused in the BFF, never a confusing engine 404.");
        }
    }

    private void requireWritableEngine(EngineConfig engine) {
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
                    "Engine '" + engine.id() + "' is registered read-only (R-GOV-04) — '" + MIGRATE_ACTION
                            + "' is rejected.");
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
                            + "' (completed or deleted?) — migration needs live executions. Nothing happened.");
        }
        if (Boolean.TRUE.equals(instance.get("suspended"))) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "instance-suspended",
                    "Instance " + instanceId + " is SUSPENDED — activate it first (the 'activate' action), then"
                            + " retry the migration. Nothing happened.");
        }
        return instance;
    }

    private void requireUnprotectedOrAdmin(Authentication auth, EngineConfig engine, String targetId) {
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
                            + protection.get().getReason() + "\" — L3 (ADMIN) action required for '" + MIGRATE_ACTION
                            + "'.");
        }
    }

    /** Tier-3 reason discipline: ALWAYS required, ≥10 chars, every environment. */
    private static String requireReason(String reason) {
        String normalized = blankToNull(reason);
        if (normalized == null) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "reason-required",
                    "'" + MIGRATE_ACTION + "' (tier " + TIER + ") always requires a reason of at least 10 characters.");
        }
        if (normalized.length() < 10) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST, "reason-too-short", "The reason must be at least 10 characters.");
        }
        return normalized;
    }

    /** Prod tier-3 escalation: type the instance's business key (or id) to confirm (SPEC §6). */
    private void requireConfirmToken(EngineConfig engine, MigrationPlan plan, String provided) {
        if (engine.environment() != EngineEnvironment.PROD) {
            return;
        }
        boolean hasBusinessKey = plan.businessKey() != null;
        String expected = hasBusinessKey ? plan.businessKey() : plan.instanceId();
        String tokenName = hasBusinessKey ? "business key" : "instance id";
        if (!expected.equals(provided)) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "confirm-token-mismatch",
                    "Migration on a PROD engine is irreversible: type the " + tokenName + " (\"" + expected
                            + "\") to confirm. Nothing happened.");
        }
    }

    /* ------------------------------ engine reads / dispatch ------------------------------ */

    /** Active activity ids WITH repeats (one per active execution) — feeds the diff + the digest. */
    private List<String> activeActivityIds(EngineConfig engine, String instanceId) {
        FlowablePage executions = client.listExecutions(engine, instanceId, EXECUTION_FETCH_CAP);
        if (executions.total() > EXECUTION_FETCH_CAP) {
            // A partial view of the active token set would make the diff + digest a lie (missed
            // flagged activities, an unstable CAS). Refuse rather than estimate on truncated data
            // (the restart-as-new "partial copy would be a lie" precedent).
            throw new GuardRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "instance-too-large",
                    "Instance " + instanceId + " has " + executions.total() + " active executions — beyond the "
                            + EXECUTION_FETCH_CAP + " the pre-check can analyze. A partial migration analysis would be"
                            + " misleading; nothing happened.");
        }
        List<String> active = new ArrayList<>();
        for (Map<String, Object> execution : executions.dataOrEmpty()) {
            Object activityId = execution.get("activityId");
            if (activityId != null) {
                active.add(String.valueOf(activityId));
            }
        }
        return active;
    }

    /** Count child instances (call activities) — they are NOT migrated (§3.7 blast-radius). */
    private int callActivityChildCount(EngineConfig engine, String instanceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("superProcessInstanceId", instanceId);
        body.put("size", CHILD_COUNT_CAP_PROBE);
        FlowablePage page = client.queryRuntimeProcessInstances(engine, body);
        return page != null ? (int) page.total() : 0;
    }

    /** Re-read the instance to PROVE the migration landed — record the observed definition id. */
    private String observedDefinitionId(EngineConfig engine, String instanceId) {
        try {
            Map<String, Object> instance = client.getRuntimeProcessInstance(engine, instanceId);
            return instance != null ? asString(instance.get("processDefinitionId")) : "(instance ended)";
        } catch (RuntimeException e) {
            return "(unverified)";
        }
    }

    /** The ONE engine call with the M4 outcome discipline — never retried, honest close-out. */
    private <T> T dispatchAudited(AuditEntry entry, String engineId, Supplier<T> call) {
        try {
            return call.get();
        } catch (CallNotPermittedException | BulkheadFullException e) {
            audit.close(entry, AuditOutcome.failed, null, "refused: circuit open / bulkhead full", false);
            throw new GuardRefusedException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "engine-shedding-load",
                    "Engine '" + engineId + "' is shedding load (circuit open) — the migration was not sent.");
        } catch (RestClientResponseException e) {
            // The engine rejected the whole document ATOMICALLY (nothing migrated) — surface its
            // verbatim message; this apply-time error is the only engine-authoritative validation.
            String body = e.getResponseBodyAsString();
            audit.close(entry, AuditOutcome.failed, e.getStatusCode().value(), body, false);
            throw new EngineRejectedException(entry.getId(), e.getStatusCode().value(), body);
        } catch (ResourceAccessException e) {
            if (notDispatched(e)) {
                audit.close(entry, AuditOutcome.failed, null, "engine unreachable: " + e.getMessage(), false);
                throw new GuardRefusedException(
                        HttpStatus.BAD_GATEWAY,
                        "engine-unreachable",
                        "Engine '" + engineId + "' is unreachable — the migration was not sent.");
            }
            // Timed out AFTER dispatch: migrate is non-idempotent — UNKNOWN, never re-fired (Verify-now).
            audit.close(entry, AuditOutcome.unknown, null, "no answer within write budget: " + e.getMessage(), false);
            throw new OutcomeUnknownException(entry.getId(), e);
        }
    }

    /* ------------------------------------ helpers ------------------------------------ */

    private List<MigrationMapping> validatedOverrides(List<MigrationMapping> mappings) {
        List<MigrationMapping> valid = new ArrayList<>();
        for (MigrationMapping mapping : mappings) {
            try {
                mapping.form(); // throws IllegalArgumentException on a malformed shape
            } catch (IllegalArgumentException e) {
                throw new GuardRefusedException(HttpStatus.BAD_REQUEST, "invalid-mapping", e.getMessage());
            }
            valid.add(mapping);
        }
        return valid;
    }

    static Map<String, Object> migrationDocument(String toProcessDefinitionId, List<MigrationMapping> overrides) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("toProcessDefinitionId", toProcessDefinitionId);
        document.put(
                "activityMappings",
                overrides.stream().map(MigrationMapping::toWire).toList());
        return document;
    }

    private static Map<String, Object> firstOrNull(FlowablePage page) {
        List<Map<String, Object>> rows = page != null ? page.dataOrEmpty() : List.of();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String definitionKeyOf(String definitionId) {
        return definitionId.split(":", 2)[0];
    }

    private static int definitionVersionOf(String definitionId) {
        return Integer.parseInt(definitionId.split(":", 3)[1]);
    }

    private static boolean tenantsMatch(String a, String b) {
        return blankToNull(a) == null ? blankToNull(b) == null : a.equals(b);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean notDispatched(ResourceAccessException e) {
        for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException || t instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }

    private static GuardRefusedException engineUnreachablePreFlight(EngineConfig engine) {
        return new GuardRefusedException(
                HttpStatus.BAD_GATEWAY,
                "engine-unreachable",
                "Engine '" + engine.id() + "' did not answer the pre-flight read — the action was not sent.");
    }

    /* --------------------------- the server-fresh plan record --------------------------- */

    private record MigrationPlan(
            EngineConfig engine,
            String instanceId,
            String fromDefinitionId,
            String fromKey,
            int fromVersion,
            String businessKey,
            ResolvedTarget target,
            List<MigrationMapping> overrides,
            List<ActivityDiffEntry> activities,
            String activityStateDigest,
            int childCount) {

        boolean executable() {
            return activities.stream().noneMatch(ActivityDiffEntry::isBlocker);
        }

        Map<String, Object> restBody() {
            return migrationDocument(target.definitionId(), overrides);
        }

        String summary() {
            long flagged =
                    activities.stream().filter(ActivityDiffEntry::isBlocker).count();
            long warnings =
                    activities.stream().filter(ActivityDiffEntry::isWarning).count();
            StringBuilder sb = new StringBuilder("Migrate this instance from v")
                    .append(fromVersion)
                    .append(" to v")
                    .append(target.version())
                    .append(". ");
            if (executable()) {
                sb.append("All ").append(activities.size()).append(" active activit(ies) map.");
            } else {
                sb.append(flagged).append(" active activit(ies) can't be auto-mapped — pick a target for each.");
            }
            if (warnings > 0) {
                sb.append(" ").append(warnings).append(" advisory warning(s).");
            }
            if (childCount > 0) {
                sb.append(" ")
                        .append(childCount)
                        .append(" child instance(s) are NOT migrated (they keep their own definition).");
            }
            return sb.toString();
        }

        String deltaStatement(String observedDefinitionId) {
            return "Instance " + instanceId + " migrated from v" + fromVersion + " to v" + target.version()
                    + " (now on "
                    + observedDefinitionId + ")."
                    + (childCount > 0 ? " " + childCount + " child instance(s) kept their own definition." : "")
                    + " IRREVERSIBLE — migrating back is a fresh forward migration to the old version, not an undo;"
                    + " work executed under the new version stands.";
        }

        Map<String, Object> auditPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schema", MIGRATE_ACTION + "/v1");
            payload.put("engineValidated", false); // the constant honesty marker (P0-6)
            payload.put("fromProcessDefinitionId", fromDefinitionId);
            payload.put("toProcessDefinitionId", target.definitionId());
            payload.put("toProcessDefinitionKey", fromKey);
            payload.put("toProcessDefinitionVersion", target.version());
            payload.put("activityMappings", restBody().get("activityMappings"));
            payload.put("bffAutoMapped", idsWithStatus(ActivityDiffEntry.Status.AUTO_MAPPED));
            payload.put("bffMappedByOverride", idsWithStatus(ActivityDiffEntry.Status.MAPPED_BY_OVERRIDE));
            payload.put(
                    "bffWarnings",
                    activities.stream()
                            .filter(ActivityDiffEntry::isWarning)
                            .map(a -> a.fromActivityId() + " (" + a.status() + ")")
                            .toList());
            payload.put("activityStateDigest", activityStateDigest);
            payload.put(
                    "activeActivities",
                    activities.stream().map(ActivityDiffEntry::fromActivityId).toList());
            payload.put("childExecutionsUnaffected", childCount);
            payload.put("businessKey", businessKey);
            payload.put("endpoint", "POST /runtime/process-instances/" + instanceId + "/migrate");
            payload.put("restBody", restBody());
            payload.put("reversibility", "IRREVERSIBLE");
            return payload;
        }

        private List<String> idsWithStatus(ActivityDiffEntry.Status status) {
            return activities.stream()
                    .filter(a -> a.status() == status)
                    .map(ActivityDiffEntry::fromActivityId)
                    .toList();
        }
    }
}
