package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 */
public record SearchResponse(List<ProcessInstanceRow> rows, Map<String, EngineResult> perEngine) {

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
