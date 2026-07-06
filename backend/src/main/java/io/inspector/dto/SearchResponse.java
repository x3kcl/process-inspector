package io.inspector.dto;

import java.util.List;
import java.util.Map;

/**
 * Aggregated fan-out result. An unreachable engine yields ok=false in perEngine
 * and the search still succeeds with partial rows (ARCHITECTURE.md §2.2).
 */
public record SearchResponse(
        List<ProcessInstanceRow> rows,
        Map<String, EngineResult> perEngine
) {
    public record EngineResult(boolean ok, long fetched, long total, String error) {
        public static EngineResult success(long fetched, long total) {
            return new EngineResult(true, fetched, total, null);
        }
        public static EngineResult failure(String error) {
            return new EngineResult(false, 0, 0, error);
        }
    }
}
