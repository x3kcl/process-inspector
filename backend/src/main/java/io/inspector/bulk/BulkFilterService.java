package io.inspector.bulk;

import io.inspector.action.GuardRefusedException;
import io.inspector.aggregate.SearchService;
import io.inspector.dto.ProcessInstanceRow;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.dto.SearchResponse;
import io.inspector.registry.EngineRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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

        // Re-resolution: the SAME plan the grid used, paged to exhaustion; the resolver owns
        // the page size (the grid's pageSize is a display concern, not a scope bound).
        SearchResponse resolved = search.resolveAllMatching(withoutPageSize(criteria), BulkJob.FILTER_ITEM_CAP);

        // Fail-closed resolution (same doctrine as the error-class retry): an engine that
        // did not answer means the member list is not trustworthy — refuse the whole submit.
        for (Map.Entry<String, SearchResponse.EngineResult> e :
                resolved.perEngine().entrySet()) {
            if (!e.getValue().ok()) {
                throw refuse(
                        HttpStatus.BAD_GATEWAY,
                        "filter-resolution-degraded",
                        "Engine '" + e.getKey() + "' did not answer the filter resolution ("
                                + e.getValue().error() + ") — the member list would be incomplete."
                                + " Nothing happened.");
            }
            // A truncated failure-lane scan means matching instances exist that this
            // resolution never saw — for a BINDING "all matching" that is a refusal, not a
            // footnote (stricter than the error-class path, which is already scoped to one
            // definition version).
            String truncated = e.getValue().dlqScan() != null
                    ? e.getValue().dlqScan()
                    : e.getValue().failingScan();
            if (truncated != null) {
                throw refuse(
                        HttpStatus.BAD_REQUEST,
                        "filter-scan-truncated",
                        "The failure-lane scan on engine '" + e.getKey() + "' hit its cap (" + truncated
                                + ") — \"all matching\" would silently be a subset. Narrow the filter"
                                + " (a definition key pushes the scan down). Nothing happened.");
            }
        }

        Map<String, BulkDtos.BulkTarget> targets = new LinkedHashMap<>();
        for (ProcessInstanceRow row : resolved.rows()) {
            targets.putIfAbsent(
                    row.compositeId(), new BulkDtos.BulkTarget(row.engineId(), row.processInstanceId(), null));
        }
        if (targets.isEmpty()) {
            throw refuse(
                    HttpStatus.CONFLICT,
                    "filter-drained",
                    "No instances currently match this filter — the set has drained since the grid"
                            + " rendered. Refresh the search. Nothing happened.");
        }
        if (targets.size() > BulkJob.FILTER_ITEM_CAP) {
            throw refuse(
                    HttpStatus.BAD_REQUEST,
                    "bulk-cap-exceeded",
                    "This filter resolves to more than " + BulkJob.FILTER_ITEM_CAP
                            + " instances — over the query-bulk hard cap. Narrow the filter."
                            + " Nothing happened.");
        }

        // Provenance for the envelope audit row: the criteria as submitted and what they
        // resolved to — alongside the full target list the envelope already records.
        Map<String, Object> filterMeta = new LinkedHashMap<>();
        filterMeta.put("criteria", criteria);
        filterMeta.put("resolvedCount", targets.size());

        BulkDtos.BulkSubmitRequest submit = new BulkDtos.BulkSubmitRequest(
                request.verb(), request.reason().trim(), request.ticketId(), null, List.copyOf(targets.values()));
        return bulk.submit(submit, auth, Map.of("filter", filterMeta), BulkJob.FILTER_ITEM_CAP, scopeLabel(criteria));
    }

    /**
     * Scope provenance (usability fix E1): a compact restatement of the criteria — statuses
     * + definition key[+version] + engines — ported from the {@code criteriaChips} notion
     * FilterBulkModal.tsx already shows the operator, kept ≤120 chars for the drawer.
     */
    private static String scopeLabel(SearchRequest criteria) {
        List<String> parts = new ArrayList<>();
        if (criteria.statuses() != null && !criteria.statuses().isEmpty()) {
            parts.add(criteria.statuses().stream().map(Enum::name).collect(Collectors.joining(" + ")));
        }
        if (criteria.processDefinitionKey() != null
                && !criteria.processDefinitionKey().isBlank()) {
            parts.add(
                    criteria.definitionVersion() != null
                            ? criteria.processDefinitionKey() + " v" + criteria.definitionVersion()
                            : criteria.processDefinitionKey());
        }
        if (criteria.engineIds() != null && !criteria.engineIds().isEmpty()) {
            parts.add("engines: " + String.join(", ", criteria.engineIds()));
        }
        String label = String.join(" · ", parts);
        return label.length() > 120 ? label.substring(0, 117) + "..." : label;
    }

    /** The 16-component record, minus the display-page bound (the resolver owns paging). */
    private static SearchRequest withoutPageSize(SearchRequest c) {
        return new SearchRequest(
                c.engineIds(),
                c.statuses(),
                c.processDefinitionKey(),
                c.definitionVersion(),
                c.businessKey(),
                c.businessKeyLike(),
                c.startedAfter(),
                c.startedBefore(),
                c.failureTimeAfter(),
                c.failureTimeBefore(),
                c.errorText(),
                c.signatureHash(),
                c.currentActivity(),
                c.variables(),
                c.sortBy(),
                null);
    }

    private static GuardRefusedException refuse(HttpStatus status, String code, String message) {
        return new GuardRefusedException(status, code, message);
    }
}
