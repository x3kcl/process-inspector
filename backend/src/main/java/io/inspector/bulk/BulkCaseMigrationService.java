package io.inspector.bulk;

import io.inspector.action.GuardRefusedException;
import io.inspector.action.TicketPolicy;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.migration.CaseCohortBulkMigration;
import io.inspector.migration.CaseCohortBulkMigration.Cohort;
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
 * Case cohort migration as a bulk job (#500) — the case sibling of {@link BulkMigrationService},
 * with the same two doors and the bulk verb {@code migrate-case}:
 *
 * <ul>
 *   <li>{@link #submit}: completed case analyses become one job over their executable sets.
 *       ADMIN on every engine, reason ≥10, ticket per engine policy, and the typed COUNT once any
 *       engine is prod.
 *   <li>{@link #continueJob}: only a prior migrate-case job's {@code not_run}/{@code failed}
 *       items, never a new target.
 * </ul>
 *
 * Both converge on {@link BulkJobService#submitCaseMigration}: the envelope audit row, the paced
 * per-engine fan-out and the dangerous-set re-auth come from there.
 */
@Service
public class BulkCaseMigrationService {

    private final BulkJobService bulk;
    private final BulkJobRepository jobs;
    private final BulkJobItemRepository items;
    private final CaseCohortBulkMigration cohorts;
    private final EngineRegistry registry;
    private final RbacAuthorizer rbac;
    private final TicketPolicy tickets;

    public BulkCaseMigrationService(
            BulkJobService bulk,
            BulkJobRepository jobs,
            BulkJobItemRepository items,
            CaseCohortBulkMigration cohorts,
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

    public static boolean isMigrateCase(String verb) {
        return BulkJobService.MIGRATE_CASE_VERB.equals(verb);
    }

    public BulkDtos.BulkJobDto submit(BulkDtos.BulkMigrateRequest request, Authentication auth) {
        List<UUID> ids =
                request.analysisIds() == null ? List.of() : List.copyOf(new LinkedHashSet<>(request.analysisIds()));
        if (ids.isEmpty()) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "analysis-required",
                    "Name at least one completed case analysis. Nothing happened.");
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
            for (String caseId : cohort.executableIds()) {
                if (!seen.add(cohort.engineId() + ":" + caseId)) {
                    throw new GuardRefusedException(
                            HttpStatus.CONFLICT,
                            "analysis-overlap",
                            "Case " + cohort.engineId() + ":" + caseId
                                    + " is in more than one analysis. Submit one of them. Nothing happened.");
                }
                targets.add(new BulkDtos.BulkTarget(cohort.engineId(), caseId, id.toString()));
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("analysisId", id.toString());
            meta.put("engineId", cohort.engineId());
            meta.put("fromCaseDefinitionId", cohort.fromCaseDefinitionId());
            meta.put("toCaseDefinitionId", cohort.toCaseDefinitionId());
            meta.put("executable", cohort.executableIds().size());
            meta.put("blockersNotSent", cohort.blockerCount());
            provenance.add(meta);
        }
        if (targets.isEmpty()) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "analysis-nothing-executable",
                    "No case in these analyses is executable — every one carries a blocker. Nothing happened.");
        }
        if (prod) {
            if (request.confirmedCount() == null) {
                throw new GuardRefusedException(
                        HttpStatus.BAD_REQUEST,
                        "bulk-migrate-confirm-count-required",
                        "This cohort includes a PRODUCTION engine — type the case count (" + targets.size()
                                + ") to confirm. Nothing happened.");
            }
            if (request.confirmedCount() != targets.size()) {
                throw new BulkCountDriftException(request.confirmedCount(), targets.size());
            }
        }
        BulkDtos.BulkSubmitRequest submit = new BulkDtos.BulkSubmitRequest(
                BulkJobService.MIGRATE_CASE_VERB,
                request.reason() == null ? null : request.reason().trim(),
                request.ticketId(),
                null,
                targets);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("analyses", provenance);
        envelope.put("prodInScope", prod);
        envelope.put("strategy", "per-case");
        String label = ids.size() == 1
                ? "case cohort migrate · analysis " + ids.get(0).toString().substring(0, 8)
                : "case cohort migrate · " + ids.size() + " analyses";
        return bulk.submitCaseMigration(submit, auth, Map.of("migration", envelope), label);
    }

    /** The drawer's continuation: a subset of a prior migrate-case job's not-run/failed items. */
    public BulkDtos.BulkJobDto continueJob(BulkDtos.BulkSubmitRequest request, Authentication auth) {
        if (request.continuedFrom() == null) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "migrate-needs-analysis",
                    "A case cohort migrate starts from a completed analysis (POST /api/bulk/migrate-cases). This"
                            + " door only continues an existing migrate-case job. Nothing happened.");
        }
        BulkJob prior = jobs.findById(request.continuedFrom())
                .filter(job -> BulkJobService.MIGRATE_CASE_VERB.equals(job.getVerb()))
                .orElseThrow(() -> new GuardRefusedException(
                        HttpStatus.NOT_FOUND,
                        "bulk-job-unknown",
                        "No migrate-case job " + request.continuedFrom() + ". Nothing happened."));
        Set<String> continuable = new HashSet<>();
        for (BulkJobItem item : items.findByJobIdOrderByOrdinal(prior.getId())) {
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
                BulkJobService.MIGRATE_CASE_VERB, request.reason(), ticketId, prior.getId(), targets);
        return bulk.submitCaseMigration(
                submit,
                auth,
                Map.of("migration", Map.of("continuation", true)),
                "case cohort migrate · continues " + prior.getId().toString().substring(0, 8));
    }

    private void requireAdmin(Authentication auth, String engineId) {
        if (!rbac.hasRoleOn(auth, Role.ADMIN, engineId)) {
            throw new GuardRefusedException(
                    HttpStatus.FORBIDDEN,
                    "rbac-denied",
                    "Case cohort migration requires ADMIN on engine '" + engineId + "'. Nothing happened.");
        }
    }

    private static String key(String engineId, String instanceId, String jobRef) {
        return engineId + "|" + instanceId + "|" + jobRef;
    }
}
