package io.inspector.bulk;

import io.inspector.action.GuardRefusedException;
import io.inspector.action.TicketPolicy;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.migration.CohortBulkMigration;
import io.inspector.migration.CohortBulkMigration.Cohort;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Cohort migration as a bulk job (#482 S4 / #496). Two doors, one verb:
 *
 * <ul>
 *   <li>{@link #submit}: one or more COMPLETED cohort analyses (any engines) become one job. The
 *       targets are each analysis's executable set — blocker-carrying instances are never sent.
 *       ADMIN on every engine, reason ≥10, ticket per engine policy, and the typed COUNT once any
 *       engine is prod.
 *   <li>{@link #continueJob}: "Continue as new job" / per-item Retry from the operations drawer.
 *       Only a prior migrate job's {@code not_run}/{@code failed} items, never a new target, so the
 *       typed count attested at the original submit stands; ADMIN and reason are re-checked.
 * </ul>
 *
 * Both converge on {@link BulkJobService#submitMigration}: protected auto-exclusion, the envelope
 * audit row, the paced per-engine fan-out and the dangerous-set re-auth all come from there.
 */
@Service
public class BulkMigrationService {

    private final BulkJobService bulk;
    private final BulkJobRepository jobs;
    private final BulkJobItemRepository items;
    private final CohortBulkMigration cohorts;
    private final EngineRegistry registry;
    private final RbacAuthorizer rbac;
    private final TicketPolicy tickets;

    public BulkMigrationService(
            BulkJobService bulk,
            BulkJobRepository jobs,
            BulkJobItemRepository items,
            CohortBulkMigration cohorts,
            EngineRegistry registry,
            RbacAuthorizer rbac,
            TicketPolicy tickets) {
        this.bulk = bulk;
        this.jobs = jobs;
        this.items = items;
        this.cohorts = cohorts;
        this.registry = registry;
        this.rbac = rbac;
        this.tickets = tickets;
    }

    public static boolean isMigrate(String verb) {
        return BulkJobService.MIGRATE_VERB.equals(verb);
    }

    public BulkDtos.BulkJobDto submit(BulkDtos.BulkMigrateRequest request, Authentication auth) {
        List<UUID> ids =
                request.analysisIds() == null ? List.of() : List.copyOf(new LinkedHashSet<>(request.analysisIds()));
        if (ids.isEmpty()) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "analysis-required",
                    "Name at least one completed analysis. Nothing happened.");
        }
        List<BulkDtos.BulkTarget> targets = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> provenance = new ArrayList<>();
        boolean prod = false;
        for (UUID id : ids) {
            Cohort cohort = cohorts.resolve(id);
            requireAdmin(auth, cohort.engineId());
            EngineEnvironment environment = registry.require(cohort.engineId()).environment();
            tickets.validate(request.ticketId(), environment);
            prod |= environment == EngineEnvironment.PROD;
            for (String instanceId : cohort.executableIds()) {
                if (!seen.add(cohort.engineId() + ":" + instanceId)) {
                    throw new GuardRefusedException(
                            HttpStatus.CONFLICT,
                            "analysis-overlap",
                            "Instance " + cohort.engineId() + ":" + instanceId
                                    + " is in more than one analysis. Submit one of them. Nothing happened.");
                }
                targets.add(new BulkDtos.BulkTarget(cohort.engineId(), instanceId, id.toString()));
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("analysisId", id.toString());
            meta.put("engineId", cohort.engineId());
            meta.put("fromProcessDefinitionId", cohort.fromDefinitionId());
            meta.put("toProcessDefinitionId", cohort.toDefinitionId());
            meta.put("executable", cohort.executableIds().size());
            meta.put("blockersNotSent", cohort.blockerCount());
            provenance.add(meta);
        }
        if (targets.isEmpty()) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "analysis-nothing-executable",
                    "No instance in these analyses is executable — every one carries a blocker. Nothing happened.");
        }
        if (prod) {
            if (request.confirmedCount() == null) {
                throw new GuardRefusedException(
                        HttpStatus.BAD_REQUEST,
                        "bulk-migrate-confirm-count-required",
                        "This cohort includes a PRODUCTION engine — type the instance count (" + targets.size()
                                + ") to confirm. Nothing happened.");
            }
            if (request.confirmedCount() != targets.size()) {
                throw new BulkCountDriftException(request.confirmedCount(), targets.size());
            }
        }
        BulkDtos.BulkSubmitRequest submit = new BulkDtos.BulkSubmitRequest(
                BulkJobService.MIGRATE_VERB,
                request.reason() == null ? null : request.reason().trim(),
                request.ticketId(),
                null,
                targets);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("analyses", provenance);
        envelope.put("prodInScope", prod);
        envelope.put("strategy", "per-instance");
        String label = ids.size() == 1
                ? "cohort migrate · analysis " + ids.get(0).toString().substring(0, 8)
                : "cohort migrate · " + ids.size() + " analyses";
        return bulk.submitMigration(submit, auth, Map.of("migration", envelope), label);
    }

    /** The drawer's continuation: a subset of a prior migrate job's not-run/failed items. */
    public BulkDtos.BulkJobDto continueJob(BulkDtos.BulkSubmitRequest request, Authentication auth) {
        if (request.continuedFrom() == null) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "migrate-needs-analysis",
                    "A cohort migrate starts from a completed analysis (POST /api/bulk/migrate). This door only"
                            + " continues an existing migrate job. Nothing happened.");
        }
        BulkJob prior = jobs.findById(request.continuedFrom())
                .filter(job -> BulkJobService.MIGRATE_VERB.equals(job.getVerb()))
                .orElseThrow(() -> new GuardRefusedException(
                        HttpStatus.NOT_FOUND,
                        "bulk-job-unknown",
                        "No migrate job " + request.continuedFrom() + ". Nothing happened."));
        Set<String> continuable = new HashSet<>();
        List<BulkJobItem> priorItems = items.findByJobIdOrderByOrdinal(prior.getId());
        for (BulkJobItem item : priorItems) {
            if (item.getState() == BulkJobItem.State.not_run || item.getState() == BulkJobItem.State.failed) {
                continuable.add(key(item.getEngineId(), item.getInstanceId(), item.getJobRef()));
            }
        }
        List<BulkDtos.BulkTarget> targets = request.items() == null ? List.of() : request.items();
        for (BulkDtos.BulkTarget target : targets) {
            if (!continuable.contains(key(target.engineId(), target.instanceId(), target.jobId()))) {
                throw new GuardRefusedException(
                        HttpStatus.BAD_REQUEST,
                        "continuation-scope-mismatch",
                        "Item " + target.engineId() + ":" + target.instanceId() + " is not a not-run or failed item"
                                + " of job " + prior.getId() + ". Nothing happened.");
            }
            requireAdmin(auth, target.engineId());
        }
        String ticketId = request.ticketId() != null ? request.ticketId() : prior.getTicketId();
        BulkDtos.BulkSubmitRequest submit = new BulkDtos.BulkSubmitRequest(
                BulkJobService.MIGRATE_VERB, request.reason(), ticketId, prior.getId(), targets);
        // A hierarchy job (#497) keeps its children-first order, and a parent is continued only
        // with its unresolved children or once they are on their target.
        Map<String, HierarchyOrder.Placement> placements =
                HierarchyOrder.carryOver(priorItems, targets, this::onTarget);
        String continues = " · continues " + prior.getId().toString().substring(0, 8);
        if (placements.isEmpty()) {
            return bulk.submitMigration(
                    submit, auth, Map.of("migration", Map.of("continuation", true)), "cohort migrate" + continues);
        }
        return bulk.submitMigration(
                submit,
                auth,
                Map.of("migration", Map.of("continuation", true, "hierarchy", true)),
                "hierarchy migrate" + continues,
                placements);
    }

    /** Read-only: is this prior item's instance on its analysis target now. A failed read is "no". */
    private boolean onTarget(BulkJobItem item) {
        try {
            return cohorts.verify(item.getJobRef(), item.getEngineId(), item.getInstanceId())
                    .migrated();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void requireAdmin(Authentication auth, String engineId) {
        if (!rbac.hasRoleOn(auth, Role.ADMIN, engineId)) {
            throw new GuardRefusedException(
                    HttpStatus.FORBIDDEN,
                    "rbac-denied",
                    "Cohort migration requires ADMIN on engine '" + engineId + "'. Nothing happened.");
        }
    }

    private static String key(String engineId, String instanceId, String jobRef) {
        return engineId + "|" + instanceId + "|" + jobRef;
    }
}
