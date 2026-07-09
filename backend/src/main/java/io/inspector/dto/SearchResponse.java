package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.inspector.dto.SearchRequest.InstanceStatus;
import java.util.List;
import java.util.Map;

/**
 * Aggregated fan-out result. An unreachable engine yields ok=false in perEngine
 * and the search still succeeds with partial rows (ARCHITECTURE.md §2.2).
 *
 * <p>Truncation honesty (ARCH §2.3): when a bounded scan leg stopped at the registry cap,
 * the per-engine envelope says so — {@code dlqScan}/{@code failingScan} carry
 * {@code "truncated@N"} (N = jobs scanned before hitting the cap) and the UI badges every
 * count derived from it as a lower bound. Never let truncated data impersonate health.
 *
 * <p>{@code statusCounts} (SPEC §8 facets) counts the candidates each plan actually
 * evaluated — after the non-status filters, before the status predicate — keyed by primary
 * chip. It contains ONLY statuses the chosen plan could observe (an INVERTED search never
 * saw ACTIVE instances, so no ACTIVE key) and inherits lower-bound semantics from any
 * truncated scan or page cap. {@code criteriaEcho} + {@code curl} are the compiled-criteria
 * echo and copy-as-cURL of SPEC Stage 1 — presentation over the request, filled by the
 * controller, never recomputed by clients.
 */
public record SearchResponse(
        List<ProcessInstanceRow> rows,
        Map<String, EngineResult> perEngine,
        Map<InstanceStatus, Long> statusCounts,
        List<String> criteriaEcho,
        String curl,
        // v2 deep paging (docs/KWAY-PAGING.md, R-SEM-22): the opaque cursor for the NEXT "Load more"
        // click (null on a normal search or at end-of-stream); depthCapped = some engine reached its
        // per-engine depth cap (surface the depth-wall filter seam); pagingCoherence = "snapshot" on a
        // deep-paged set (the "loaded more as of HH:MM — Refresh to reset" seam line). Null/false on
        // the normal single-shot search path.
        String nextCursor,
        boolean depthCapped,
        String pagingCoherence) {

    /**
     * Pre-deep-paging 5-arg shape → no cursor, not depth-capped, single-shot coherence. Keeps the
     * normal aggregation path and existing tests off constructor churn (unit-test-patterns).
     */
    public SearchResponse(
            List<ProcessInstanceRow> rows,
            Map<String, EngineResult> perEngine,
            Map<InstanceStatus, Long> statusCounts,
            List<String> criteriaEcho,
            String curl) {
        this(rows, perEngine, statusCounts, criteriaEcho, curl, null, false, null);
    }

    /** Controller-side decoration: same aggregation result, presentation fields filled, deep-page markers preserved. */
    public SearchResponse withPresentation(List<String> criteriaEcho, String curl) {
        return new SearchResponse(
                rows, perEngine, statusCounts, criteriaEcho, curl, nextCursor, depthCapped, pagingCoherence);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EngineResult(
            boolean ok,
            long fetched,
            long total, // engine-reported total for the primary query; lower bound if truncated
            String error,
            String dlqScan, // null = complete; "truncated@N" = dead-letter scan hit the cap
            String failingScan // null = complete; "truncated@N" = withException scan hit the cap
            ) {
        public static EngineResult success(long fetched, long total) {
            return new EngineResult(true, fetched, total, null, null, null);
        }

        public static EngineResult success(long fetched, long total, String dlqScan, String failingScan) {
            return new EngineResult(true, fetched, total, null, dlqScan, failingScan);
        }

        public static EngineResult failure(String error) {
            return new EngineResult(false, 0, 0, error, null, null);
        }
    }
}
