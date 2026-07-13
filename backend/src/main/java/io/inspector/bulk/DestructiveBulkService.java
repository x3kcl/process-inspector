package io.inspector.bulk;

import io.inspector.action.ActionVerb;
import io.inspector.aggregate.SearchService;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.dto.ProcessInstanceRow;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * The tier-4 destructive-bulk wizard's backend (SPEC §6/§7, issue #100): "terminate every
 * matching instance" from the results grid, scaled up with the guard ladder's heaviest tier —
 * scope enumeration re-resolved server-fresh at submit, a mandatory narrowing filter (unscoped
 * destructive bulk is refused outright), and — on any PROD-touching scope — a typed COUNT
 * attestation checked against that same fresh resolution, never a stale preview.
 *
 * <p>This PR ships {@code terminate-delete} only. {@code delete-deadletter}-at-scale needs a
 * JOB-level (not instance-level) scope resolver — a distinct, mechanically similar follow-up,
 * documented rather than silently dropped (IMPLEMENTATION-PLAN.md).
 *
 * <p>{@link #preview} is read-only (no audit row, mirrors migrate/change-state preview) and
 * MUST NOT be trusted by {@link #submit} — every guard, including the resolution itself, re-runs
 * server-fresh at submit against the LIVE state, the same compare-and-set spirit as migrate.
 */
@Service
public class DestructiveBulkService {

    /** Preview's display cap — the wizard's expandable list, never the binding scope. */
    private static final int SAMPLE_ROW_CAP = 50;

    private final SearchService search;
    private final BulkJobService bulk;
    private final EngineRegistry registry;
    private final RbacAuthorizer rbac;

    public DestructiveBulkService(
            SearchService search, BulkJobService bulk, EngineRegistry registry, RbacAuthorizer rbac) {
        this.search = search;
        this.bulk = bulk;
        this.registry = registry;
        this.rbac = rbac;
    }

    /** Read-only scope enumeration (SPEC §6 tier 4) — no audit row, never dispatches. */
    public BulkDtos.BulkDestructivePreview preview(BulkDtos.BulkDestructiveRequest request, Authentication auth) {
        SearchRequest criteria = requireCriteria(request.verb(), request.criteria());
        requireAdminOnTargetEngines(auth, criteria);
        Map<String, BulkDtos.BulkTarget> targets =
                BulkFilterResolution.resolveExhaustively(search, criteria, BulkJob.ITEM_CAP);
        return buildPreview(targets);
    }

    /**
     * The binding submit: re-validates and re-resolves everything from scratch — the preview is
     * advisory only. Refuses on scope drift (typed count vs. fresh resolution) rather than
     * silently acting on whatever the fresh scope turned out to be.
     */
    public BulkDtos.BulkJobDto submit(BulkDtos.BulkDestructiveRequest request, Authentication auth) {
        SearchRequest criteria = requireCriteria(request.verb(), request.criteria());
        ActionVerb verb = ActionVerb.fromPath(request.verb()).orElseThrow();
        requireReason(request.reason());
        requireAdminOnTargetEngines(auth, criteria);

        Map<String, BulkDtos.BulkTarget> targets =
                BulkFilterResolution.resolveExhaustively(search, criteria, BulkJob.ITEM_CAP);
        if (targets.isEmpty()) {
            throw BulkFilterResolution.refuse(
                    HttpStatus.CONFLICT,
                    "filter-drained",
                    "No instances currently match this filter — the set has drained since you last"
                            + " looked. Refresh and reconsider. Nothing happened.");
        }

        // Tier-4's typed-count attestation (SPEC §6): required once the FRESH scope touches PROD —
        // checked here, against the resolution ABOVE, never the wizard's earlier preview snapshot.
        boolean prodInScope = targetsTouchProd(targets);
        if (prodInScope) {
            if (request.confirmedCount() == null) {
                throw BulkFilterResolution.refuse(
                        HttpStatus.BAD_REQUEST,
                        "bulk-destructive-confirm-count-required",
                        "This scope includes a PRODUCTION engine — type the resolved instance count" + " ("
                                + targets.size() + ") to confirm. Nothing happened.");
            }
            if (request.confirmedCount() != targets.size()) {
                throw new BulkCountDriftException(request.confirmedCount(), targets.size());
            }
        }

        Map<String, Object> filterMeta = new LinkedHashMap<>();
        filterMeta.put("criteria", criteria);
        filterMeta.put("resolvedCount", targets.size());
        filterMeta.put("prodInScope", prodInScope);

        BulkDtos.BulkSubmitRequest submit = new BulkDtos.BulkSubmitRequest(
                verb.path(), request.reason().trim(), request.ticketId(), null, List.copyOf(targets.values()));
        return bulk.submitDestructive(
                submit, auth, Map.of("destructive", filterMeta), BulkFilterResolution.scopeLabel(criteria));
    }

    /* ------------------------------- guards ------------------------------- */

    private SearchRequest requireCriteria(String verbPath, SearchRequest criteria) {
        ActionVerb verb = ActionVerb.fromPath(verbPath != null ? verbPath : "")
                .filter(v -> v == ActionVerb.TERMINATE_DELETE)
                .orElseThrow(() -> BulkFilterResolution.refuse(
                        HttpStatus.BAD_REQUEST,
                        "bulk-verb-not-allowed",
                        "The destructive-bulk wizard supports terminate-delete only in this release"
                                + " (delete-deadletter-at-scale is a documented fast-follow). Nothing happened."));
        if (criteria == null) {
            throw BulkFilterResolution.refuse(
                    HttpStatus.BAD_REQUEST,
                    "filter-criteria-required",
                    "Destructive bulk needs the search criteria. Nothing happened.");
        }
        List<InstanceStatus> statuses = criteria.statuses();
        if (statuses == null || statuses.isEmpty()) {
            throw BulkFilterResolution.refuse(
                    HttpStatus.BAD_REQUEST,
                    "filter-statuses-required",
                    "Destructive bulk needs explicit status chips — an open-ended status set would sweep"
                            + " instances the verb cannot act on. Nothing happened.");
        }
        if (statuses.contains(InstanceStatus.COMPLETED)) {
            throw BulkFilterResolution.refuse(
                    HttpStatus.BAD_REQUEST,
                    "filter-completed-not-actionable",
                    "COMPLETED instances cannot be bulk-acted on — drop the COMPLETED chip first."
                            + " Nothing happened.");
        }
        requireNarrowed(criteria);
        if (criteria.engineIds() != null) {
            criteria.engineIds().forEach(registry::require);
        }
        return criteria;
    }

    /**
     * SPEC §6 tier 4: "Refuse-unscoped — no destructive bulk without at least one narrowing
     * filter." Status chips alone (or an engine subset alone) still describe an unbounded
     * "every instance of this status, fleet-wide" sweep — a real narrowing dimension is
     * mandatory: a definition, a business key, an error class, an activity, a variable filter,
     * or a time window.
     */
    private void requireNarrowed(SearchRequest criteria) {
        boolean narrowed = notBlank(criteria.processDefinitionKey())
                || notBlank(criteria.businessKey())
                || notBlank(criteria.businessKeyLike())
                || notBlank(criteria.currentActivity())
                || notBlank(criteria.errorText())
                || notBlank(criteria.signatureHash())
                || (criteria.variables() != null && !criteria.variables().isEmpty())
                || criteria.startedAfter() != null
                || criteria.startedBefore() != null
                || criteria.failureTimeAfter() != null
                || criteria.failureTimeBefore() != null;
        if (!narrowed) {
            throw BulkFilterResolution.refuse(
                    HttpStatus.BAD_REQUEST,
                    "bulk-destructive-unscoped",
                    "Destructive bulk refuses an unscoped sweep — narrow by definition, business key,"
                            + " error class, activity, a variable filter, or a time window first."
                            + " Nothing happened.");
        }
    }

    private void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() < 10) {
            throw BulkFilterResolution.refuse(
                    HttpStatus.BAD_REQUEST, "reason-too-short", "The reason must be at least 10 characters.");
        }
    }

    /**
     * Fail fast (issue #100 design finding): a non-ADMIN operator's submit would otherwise burn
     * an entire job's per-item report on RBAC denials. Checked against the NAMED engines, or —
     * an unnamed scope reaching every engine — the WHOLE fleet, so "no engines named" cannot
     * quietly dodge the floor.
     */
    private void requireAdminOnTargetEngines(Authentication auth, SearchRequest criteria) {
        List<String> engineIds =
                criteria.engineIds() != null && !criteria.engineIds().isEmpty()
                        ? criteria.engineIds()
                        : registry.all().stream().map(EngineConfig::id).toList();
        for (String engineId : engineIds) {
            if (!rbac.hasRoleOn(auth, Role.ADMIN, engineId)) {
                throw BulkFilterResolution.refuse(
                        HttpStatus.FORBIDDEN,
                        "bulk-destructive-rbac-denied",
                        "ADMIN on engine '" + engineId + "' is required for destructive bulk." + " Nothing happened.");
            }
        }
    }

    private boolean targetsTouchProd(Map<String, BulkDtos.BulkTarget> targets) {
        return targets.values().stream()
                .map(BulkDtos.BulkTarget::engineId)
                .distinct()
                .anyMatch(id -> registry.require(id).environment() == EngineEnvironment.PROD);
    }

    private BulkDtos.BulkDestructivePreview buildPreview(Map<String, BulkDtos.BulkTarget> targets) {
        Map<String, Long> perEngine = new LinkedHashMap<>();
        for (BulkDtos.BulkTarget t : targets.values()) {
            perEngine.merge(t.engineId(), 1L, Long::sum);
        }
        List<ProcessInstanceRow> sample = targets.values().stream()
                .limit(SAMPLE_ROW_CAP)
                .map(t -> new ProcessInstanceRow(
                        t.engineId() + ":" + t.instanceId(),
                        t.engineId(),
                        null,
                        null,
                        t.instanceId(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "bpmn",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null))
                .toList();
        return new BulkDtos.BulkDestructivePreview(
                targets.size(), perEngine, sample, targets.size() > SAMPLE_ROW_CAP, targetsTouchProd(targets));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
