package io.inspector.migration;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.inspector.action.GuardRefusedException;
import io.inspector.client.FlowableEngineClient;
import io.inspector.client.FlowableEngineClient.FlowablePage;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import io.inspector.surgery.BpmnStructure;
import io.inspector.surgery.BpmnStructureService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

/**
 * Instance migration (SPEC §5 tier-3 "Migrate instance") — move a running instance forward to
 * another deployed version of the SAME process key, without terminate+restart. Clones the
 * {@link io.inspector.surgery.FlowSurgeryService} rail order (RBAC → capability → writable →
 * server-fresh restatement → target resolution) and its BFF-simulation honesty.
 *
 * <p><b>P0 spike (2026-07-09):</b> Flowable's REST API exposes NO migration validator on any
 * version — only the execute route {@code POST /runtime/process-instances/{id}/migrate}
 * (document field {@code activityMappings}). So this feature's "preview" is a BFF static
 * auto-map check ({@link MigrationDiff}), an explicit Inspector estimate — never an engine
 * validation. This class is the S1 read-only preview; execute + audit land in S2.
 */
@Service
public class MigrationService {

    public static final String MIGRATE_ACTION = "migrate-instance";
    private static final int TIER = 3;
    private static final int CHILD_COUNT_CAP_PROBE = 1;

    private final EngineRegistry registry;
    private final FlowableEngineClient client;
    private final BpmnStructureService structures;
    private final RbacAuthorizer rbac;

    public MigrationService(
            EngineRegistry registry,
            FlowableEngineClient client,
            BpmnStructureService structures,
            RbacAuthorizer rbac) {
        this.registry = registry;
        this.client = client;
        this.structures = structures;
        this.rbac = rbac;
    }

    /**
     * The static pre-check: run every BLOCKING guard against live state, resolve+pin the target,
     * and classify the instance's active activities against the target model. Read-only — no
     * audit row (mirrors {@code previewChangeState}). Execute (S2) re-plans server-fresh and never
     * trusts a previously issued preview.
     */
    public MigrationPreview preview(String engineId, String instanceId, MigrationRequest request, Authentication auth) {
        EngineConfig engine = registry.require(engineId);
        requireMigrateRole(auth, engine);
        requireMigrationCapability(engine);
        requireWritableEngine(engine);

        Map<String, Object> instance = restateRunningInstance(engine, instanceId);
        String fromDefinitionId = String.valueOf(instance.get("processDefinitionId"));
        String fromKey = definitionKeyOf(fromDefinitionId);
        int fromVersion = definitionVersionOf(fromDefinitionId);
        String fromTenant = asString(instance.get("tenantId"));

        ResolvedTarget target = resolveTarget(engine, request, fromKey, fromVersion, fromTenant);

        List<MigrationMapping> overrides = validatedOverrides(request.mappingsOrEmpty());

        List<ActivityDiffEntry> activities;
        int childCount;
        try {
            BpmnStructure sourceModel = structures.structureOf(engine, fromDefinitionId);
            BpmnStructure targetModel = structures.structureOf(engine, target.definitionId());
            Set<String> activeIds = activeActivityIds(engine, instanceId);
            activities = MigrationDiff.diff(
                    MigrationDiff.of(sourceModel), MigrationDiff.of(targetModel), activeIds, overrides);
            childCount = callActivityChildCount(engine, instanceId);
        } catch (CallNotPermittedException | BulkheadFullException | ResourceAccessException e) {
            throw engineUnreachablePreFlight(engine);
        }

        // `executable` reflects the ACTIVE activities only, by design (decision P0-1): the engine
        // migrates by moving current tokens and only requires mappings for token-holding
        // activities — a renamed but INACTIVE activity is not a blocker. Deeper apply-time issues
        // the static diff cannot see (MI parents, scripts) stay the engine's job at execute; the
        // banner states this and never claims the engine validated the migration.
        boolean executable = activities.stream().noneMatch(ActivityDiffEntry::isBlocker);
        Map<String, Object> restBody = migrationDocument(target.definitionId(), overrides);
        String summary = summarize(activities, fromVersion, target.version(), childCount, executable);

        return new MigrationPreview(
                engineId,
                instanceId,
                fromDefinitionId,
                fromKey,
                fromVersion,
                target.definitionId(),
                target.version(),
                false,
                executable,
                activities,
                childCount,
                "POST",
                "/runtime/process-instances/" + instanceId + "/migrate",
                restBody,
                summary,
                MigrationPreview.BANNER);
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

    private Set<String> activeActivityIds(EngineConfig engine, String instanceId) {
        FlowablePage executions = client.listExecutions(engine, instanceId, 200);
        Set<String> active = new HashSet<>();
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

    /* ----------------------------------- helpers ----------------------------------- */

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

    private static Map<String, Object> migrationDocument(
            String toProcessDefinitionId, List<MigrationMapping> overrides) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("toProcessDefinitionId", toProcessDefinitionId);
        document.put(
                "activityMappings",
                overrides.stream().map(MigrationMapping::toWire).toList());
        return document;
    }

    private static String summarize(
            List<ActivityDiffEntry> activities, int fromVersion, int toVersion, int childCount, boolean executable) {
        long flagged = activities.stream().filter(ActivityDiffEntry::isBlocker).count();
        long warnings = activities.stream().filter(ActivityDiffEntry::isWarning).count();
        StringBuilder sb = new StringBuilder("Migrate this instance from v")
                .append(fromVersion)
                .append(" to v")
                .append(toVersion)
                .append(". ");
        if (executable) {
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
                    .append(" child instance(s) are NOT migrated (they keep their own" + " definition).");
        }
        return sb.toString();
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

    private static GuardRefusedException engineUnreachablePreFlight(EngineConfig engine) {
        return new GuardRefusedException(
                HttpStatus.BAD_GATEWAY,
                "engine-unreachable",
                "Engine '" + engine.id() + "' did not answer the pre-flight read — the action was not sent.");
    }
}
