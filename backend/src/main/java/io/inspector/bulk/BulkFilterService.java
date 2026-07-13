package io.inspector.bulk;

import io.inspector.action.GuardRefusedException;
import io.inspector.aggregate.SearchService;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.registry.EngineRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * v1.x fast follow #2 (SPEC §7): select-all-matching-filter bulk from the results grid.
 *
 * <p>Server-side re-resolution is BINDING: the browser submits the {@link SearchRequest}
 * criteria it is looking at — never a resolved ID list — and the BFF re-executes the SAME
 * M2a plan, paged to exhaustion ({@link SearchService#resolveAllMatching}), at execution
 * time. The resolved composite IDs are recorded in the envelope audit row BEFORE anything
 * dispatches (the {@code targets} list {@link BulkJobService#submit} writes), then the
 * targets ride the staggered per-engine fan-out under the {@link BulkJob#FILTER_ITEM_CAP}
 * query-bulk hard cap.
 *
 * <p>"All matching" must never silently mean "some of the matching": a degraded engine, a
 * truncated failure-lane scan, or an over-cap candidate pool refuses the WHOLE submit with
 * guidance, rather than acting on a subset the operator never saw.
 */
@Service
public class BulkFilterService {

    private final SearchService search;
    private final BulkJobService bulk;
    private final EngineRegistry registry;

    public BulkFilterService(SearchService search, BulkJobService bulk, EngineRegistry registry) {
        this.search = search;
        this.bulk = bulk;
        this.registry = registry;
    }

    public BulkDtos.BulkJobDto submit(BulkDtos.BulkFilterRequest request, Authentication auth) {
        SearchRequest criteria = request.criteria();
        if (criteria == null) {
            throw refuse(
                    HttpStatus.BAD_REQUEST,
                    "filter-criteria-required",
                    "Select-all-matching needs the search criteria. Nothing happened.");
        }
        // A filter bulk acts on instances the operator never enumerated — the reason is
        // mandatory (same rule as every other bulk door, BulkJobService#submit).
        if (request.reason() == null
                || request.reason().isBlank()
                || request.reason().trim().length() < 10) {
            throw refuse(HttpStatus.BAD_REQUEST, "reason-too-short", "The reason must be at least 10 characters.");
        }
        List<InstanceStatus> statuses = criteria.statuses();
        if (statuses == null || statuses.isEmpty()) {
            throw refuse(
                    HttpStatus.BAD_REQUEST,
                    "filter-statuses-required",
                    "Select-all-matching needs explicit status chips — an open-ended status set would"
                            + " sweep instances the verb cannot act on. Nothing happened.");
        }
        if (statuses.contains(InstanceStatus.COMPLETED)) {
            throw refuse(
                    HttpStatus.BAD_REQUEST,
                    "filter-completed-not-actionable",
                    "COMPLETED instances cannot be bulk-acted on — drop the COMPLETED chip from the"
                            + " filter first. Nothing happened.");
        }
        if (criteria.engineIds() != null) {
            criteria.engineIds().forEach(registry::require);
        }

        // Re-resolution: the SAME plan the grid used, paged to exhaustion (shared with the
        // destructive-bulk wizard, BulkFilterResolution).
        Map<String, BulkDtos.BulkTarget> targets =
                BulkFilterResolution.resolveExhaustively(search, criteria, BulkJob.FILTER_ITEM_CAP);
        if (targets.isEmpty()) {
            throw refuse(
                    HttpStatus.CONFLICT,
                    "filter-drained",
                    "No instances currently match this filter — the set has drained since the grid"
                            + " rendered. Refresh the search. Nothing happened.");
        }

        // Provenance for the envelope audit row: the criteria as submitted and what they
        // resolved to — alongside the full target list the envelope already records.
        Map<String, Object> filterMeta = new LinkedHashMap<>();
        filterMeta.put("criteria", criteria);
        filterMeta.put("resolvedCount", targets.size());

        BulkDtos.BulkSubmitRequest submit = new BulkDtos.BulkSubmitRequest(
                request.verb(), request.reason().trim(), request.ticketId(), null, List.copyOf(targets.values()));
        return bulk.submit(
                submit,
                auth,
                Map.of("filter", filterMeta),
                BulkJob.FILTER_ITEM_CAP,
                BulkFilterResolution.scopeLabel(criteria));
    }

    private static GuardRefusedException refuse(HttpStatus status, String code, String message) {
        return new GuardRefusedException(status, code, message);
    }
}
