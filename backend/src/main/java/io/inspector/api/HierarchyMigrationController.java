package io.inspector.api;

import io.inspector.bulk.BulkDtos;
import io.inspector.bulk.HierarchyBulkMigrationService;
import io.inspector.migration.HierarchyPlanRequests;
import io.inspector.migration.HierarchyPlanService;
import io.inspector.migration.HierarchyPlanView;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hierarchy-aware migration (#482 S5 / #497) — explicit whitelisted routes, never a generic
 * proxy. The plan routes only read the engine and store the plan; the one mutating route submits
 * the plan as a tracked bulk job. The door check is the tier-3 ADMIN floor; the services re-check
 * ADMIN on the plan's own engine.
 */
@RestController
@RequestMapping("/api")
public class HierarchyMigrationController {

    private final HierarchyPlanService plans;
    private final HierarchyBulkMigrationService execution;

    public HierarchyMigrationController(HierarchyPlanService plans, HierarchyBulkMigrationService execution) {
        this.plans = plans;
        this.execution = execution;
    }

    /** Enumerate the running call-activity tree under the root(s) and store it. Does not migrate. */
    @PostMapping("/migration/hierarchy/plans")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'ADMIN', #engineId)")
    public HierarchyPlanView createPlan(
            @RequestParam String engineId,
            @RequestBody HierarchyPlanRequests.HierarchyPlanCreate request,
            Authentication authentication) {
        return plans.create(engineId, request, authentication);
    }

    /** The stored plan: tree, per-definition groups with their status, and whether it can be submitted. */
    @GetMapping("/migration/hierarchy/plans/{id}")
    @PreAuthorize("isAuthenticated()")
    public HierarchyPlanView getPlan(@PathVariable UUID id, Authentication authentication) {
        return plans.get(id, authentication);
    }

    /** Configure (target + mappings, estimated per instance) or skip one source definition. Does not migrate. */
    @PutMapping("/migration/hierarchy/plans/{id}/groups")
    @PreAuthorize("@rbac.atLeast(authentication, 'ADMIN')")
    public HierarchyPlanView decideGroup(
            @PathVariable UUID id,
            @RequestBody HierarchyPlanRequests.HierarchyPlanDecide request,
            Authentication authentication) {
        return plans.decide(id, request, authentication);
    }

    /**
     * Execute a plan: one tracked bulk job, deepest level first, a parent only after every planned
     * child reached its target.
     */
    @PostMapping("/bulk/migrate-hierarchy")
    @PreAuthorize("@rbac.atLeast(authentication, 'ADMIN')")
    public BulkDtos.BulkJobDto submit(
            @RequestBody BulkDtos.BulkMigrateHierarchyRequest request, Authentication authentication) {
        return execution.submit(request, authentication);
    }
}
