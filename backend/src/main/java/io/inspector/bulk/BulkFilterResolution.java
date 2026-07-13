package io.inspector.bulk;

import io.inspector.action.GuardRefusedException;
import io.inspector.aggregate.SearchService;
import io.inspector.dto.ProcessInstanceRow;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;

/**
 * The exhaustive, fail-closed filter-to-target resolution shared by {@link BulkFilterService}
 * (v1.x #2) and {@link DestructiveBulkService} (tier-4 wizard, issue #100): re-run the SAME
 * search plan to exhaustion at execution time — never trust the grid snapshot — and refuse the
 * WHOLE submit (never a silent subset) if any engine leg is degraded or a failure-lane scan was
 * truncated. "All matching" must never silently mean "some of the matching."
 */
final class BulkFilterResolution {

    private BulkFilterResolution() {}

    /** Re-resolves {@code criteria} to exhaustion (bounded by {@code cap}); never returns a partial set. */
    static Map<String, BulkDtos.BulkTarget> resolveExhaustively(SearchService search, SearchRequest criteria, int cap) {
        SearchResponse resolved = search.resolveAllMatching(withoutPageSize(criteria), cap);

        // Fail-closed resolution (same doctrine as the error-class retry): an engine that did not
        // answer means the member list is not trustworthy — refuse the whole submit.
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
            // A truncated failure-lane scan means matching instances exist that this resolution
            // never saw — for a BINDING "all matching" that is a refusal, not a footnote.
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
        if (targets.size() > cap) {
            throw refuse(
                    HttpStatus.BAD_REQUEST,
                    "bulk-cap-exceeded",
                    "This filter resolves to more than " + cap + " instances — over the query-bulk hard cap."
                            + " Narrow the filter. Nothing happened.");
        }
        return targets;
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

    static GuardRefusedException refuse(HttpStatus status, String code, String message) {
        return new GuardRefusedException(status, code, message);
    }

    /**
     * Scope provenance (usability fix E1): a compact restatement of the criteria — statuses +
     * definition key[+version] + engines — the same shape {@code criteriaChips()}
     * (FilterBulkModal.tsx) shows the operator, kept ≤120 chars for the drawer.
     */
    static String scopeLabel(SearchRequest criteria) {
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
}
