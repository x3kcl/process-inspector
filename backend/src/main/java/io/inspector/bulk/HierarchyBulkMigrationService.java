package io.inspector.bulk;

import io.inspector.action.GuardRefusedException;
import io.inspector.action.TicketPolicy;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.migration.HierarchyPlanService;
import io.inspector.migration.HierarchyPlanService.Execution;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Hierarchy-aware migration as ONE bulk job (#482 S5 / #497): a stored plan's configured groups,
 * children first, then parent. Same verb ({@code migrate-instance}), same per-item rails and the
 * same door as cohort migration ({@link BulkJobService#submitMigration}) — this class only adds
 * the tree: each item's level and planned parent, which {@link HierarchyOrder} turns into
 * deepest-level-first dispatch and "a child that did not reach its target blocks its parent".
 *
 * <p>One job means one envelope audit row and one audit correlation id for the whole plan: every
 * item's {@code migrate-instance/v2} row is written on a worker that inherits the submit
 * request's id.
 *
 * <p>Rails at submit: ADMIN on the plan's engine, reason ≥10, ticket per engine policy, the typed
 * COUNT of the executable set on prod, dangerous-set re-auth and protected auto-exclusion (both in
 * the shared submit). An instance the analysis blocked is listed {@code not_run} and never sent.
 */
@Service
public class HierarchyBulkMigrationService {

    private final BulkJobService bulk;
    private final HierarchyPlanService plans;
    private final EngineRegistry registry;
    private final RbacAuthorizer rbac;
    private final TicketPolicy tickets;

    public HierarchyBulkMigrationService(
            BulkJobService bulk,
            HierarchyPlanService plans,
            EngineRegistry registry,
            RbacAuthorizer rbac,
            TicketPolicy tickets) {
        this.bulk = bulk;
        this.plans = plans;
        this.registry = registry;
        this.rbac = rbac;
        this.tickets = tickets;
    }

    public BulkDtos.BulkJobDto submit(BulkDtos.BulkMigrateHierarchyRequest request, Authentication auth) {
        if (request.planId() == null) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST, "hierarchy-plan-required", "Name the plan to execute. Nothing happened.");
        }
        // The role check comes first: a plan's refusal text names definitions and instances.
        String engineId = plans.engineOf(request.planId());
        if (!rbac.hasRoleOn(auth, Role.ADMIN, engineId)) {
            throw new GuardRefusedException(
                    HttpStatus.FORBIDDEN,
                    "rbac-denied",
                    "Hierarchy migration requires ADMIN on engine '" + engineId + "'. Nothing happened.");
        }
        Execution execution = plans.execution(request.planId());
        EngineEnvironment environment = registry.require(engineId).environment();
        tickets.validate(request.ticketId(), environment);
        boolean prod = environment == EngineEnvironment.PROD;
        if (prod) {
            if (request.confirmedCount() == null) {
                throw new GuardRefusedException(
                        HttpStatus.BAD_REQUEST,
                        "bulk-migrate-confirm-count-required",
                        "This plan is on a PRODUCTION engine — type the instance count (" + execution.executableCount()
                                + ") to confirm. Nothing happened.");
            }
            if (request.confirmedCount() != execution.executableCount()) {
                throw new BulkCountDriftException(request.confirmedCount(), execution.executableCount());
            }
        }
        List<BulkDtos.BulkTarget> targets = new ArrayList<>();
        Map<String, HierarchyOrder.Placement> placements = new HashMap<>();
        for (HierarchyPlanService.Item item : execution.items()) {
            targets.add(new BulkDtos.BulkTarget(engineId, item.instanceId(), item.analysisId()));
            placements.put(
                    HierarchyOrder.key(engineId, item.instanceId()),
                    new HierarchyOrder.Placement(item.level(), item.parentInstanceId(), item.notSentReason()));
        }
        BulkDtos.BulkSubmitRequest submit = new BulkDtos.BulkSubmitRequest(
                BulkJobService.MIGRATE_VERB,
                request.reason() == null ? null : request.reason().trim(),
                request.ticketId(),
                null,
                targets);
        Map<String, Object> envelope = new LinkedHashMap<>(execution.provenance());
        envelope.put("executable", execution.executableCount());
        envelope.put("notSent", targets.size() - execution.executableCount());
        envelope.put("prodInScope", prod);
        return bulk.submitMigration(
                submit,
                auth,
                Map.of("migration", envelope),
                "hierarchy migrate · plan " + request.planId().toString().substring(0, 8),
                placements);
    }
}
