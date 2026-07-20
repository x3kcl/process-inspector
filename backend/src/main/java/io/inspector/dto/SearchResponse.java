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
        String pagingCoherence,
        // #279 signature-generation honesty. Non-null ONLY when the search filtered on a
        // signatureHash whose generation is not the current ErrorSignatureNormalizer.ALGO_VERSION
        // — so an empty grid can say WHY (a retired fingerprint generation) instead of reading as
        // a confirmed zero. A known-mismatch (stamped, non-current) short-circuits to zero rows
        // without touching an engine; an unstamped legacy link runs the search but carries the
        // advisory so a zero result is not misread. Omitted (NON_NULL) on every ordinary search.
        SignatureGeneration signatureGeneration) {

    /**
     * The signature-generation notice attached to a signature-filtered search whose link was NOT
     * built under the current normalizer generation (#279, R-SEM-03 needs-re-binding doctrine —
     * the read-path analogue of the incident ledger's {@code IncidentSummary.currentGeneration}).
     *
     * @param current               whether the filtered signature is the current generation (always
     *                              false when this notice is present — a current link needs none)
     * @param requestedAlgoVersion  the generation the link carried, or {@code null} for a legacy /
     *                              unstamped link (assumed-UNKNOWN generation, #279 decision)
     * @param currentAlgoVersion    {@code ErrorSignatureNormalizer.ALGO_VERSION} at read time
     * @param reason                a human sentence for the empty-state
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SignatureGeneration(
            boolean current, Integer requestedAlgoVersion, int currentAlgoVersion, String reason) {

        /**
         * True when the link is a KNOWN older/newer generation (stamped, non-current): its hash
         * provably matches no freshly-computed (current-generation) hash, so the search can
         * short-circuit to zero without touching an engine. An unstamped legacy link ({@code
         * requestedAlgoVersion == null}) is NOT provably empty — it might carry a current hash —
         * so it runs the search and this notice is advisory only.
         */
        public boolean provablyEmpty() {
            return requestedAlgoVersion != null;
        }
    }

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

    /**
     * Pre-#279 8-arg shape (no {@code signatureGeneration}) → an ordinary search that carries no
     * generation notice. Keeps the deep-paging paths and existing tests off constructor churn.
     */
    public SearchResponse(
            List<ProcessInstanceRow> rows,
            Map<String, EngineResult> perEngine,
            Map<InstanceStatus, Long> statusCounts,
            List<String> criteriaEcho,
            String curl,
            String nextCursor,
            boolean depthCapped,
            String pagingCoherence) {
        this(rows, perEngine, statusCounts, criteriaEcho, curl, nextCursor, depthCapped, pagingCoherence, null);
    }

    /** Controller-side decoration: same aggregation result, presentation fields filled, markers preserved. */
    public SearchResponse withPresentation(List<String> criteriaEcho, String curl) {
        return new SearchResponse(
                rows,
                perEngine,
                statusCounts,
                criteriaEcho,
                curl,
                nextCursor,
                depthCapped,
                pagingCoherence,
                signatureGeneration);
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
