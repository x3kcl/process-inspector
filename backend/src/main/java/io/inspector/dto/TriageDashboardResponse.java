package io.inspector.dto;

import java.util.List;
import java.util.Map;

/**
 * GET /api/triage — the Stage 0 landing payload: "what is broken, how much, where" in
 * zero keystrokes (SPEC §4 Stage 0). Aggregation-independent by construction: status
 * counts are query TOTALS (size=1), job lanes come from the M1 health probe, error
 * groups from the dedicated capped failure-lane scans — never the grid-search plan.
 *
 * Served from a short-lived BFF cache (thundering-herd protection); {@code asOf} is the
 * aggregation instant so the UI can render cache age next to Refresh.
 */
public record TriageDashboardResponse(
        String asOf, // ISO-8601 UTC aggregation instant (cache stamp)
        List<EngineDto> engines, // the M1 health strip: version, lanes, alarms
        Map<String, Long> statusCounts, // global ACTIVE/SUSPENDED/COMPLETED, ok-engines only
        Map<String, Map<String, Long>> statusCountsByEngine, // engineId → status → total
        List<ErrorGroup> errorGroups, // sorted by total desc — the triage centerpiece
        Map<String, PerEngineTriage> perEngine) {

    /**
     * Per-engine honesty envelope (R-SEM-12): a down engine is an entry with
     * {@code ok=false}, never a failed response; a capped failure scan marks
     * {@code dlqScan="truncated@N"} (N = rows actually scanned) and every count derived
     * from it becomes a labeled lower bound in the UI.
     */
    public record PerEngineTriage(boolean ok, String error, String dlqScan) {}
}
