package io.inspector.dto;

import java.util.List;

/**
 * {@code GET /api/incidents} (R-BAU-10, INCIDENT-LEDGER.md §6, issue #308): the bounded ledger
 * list — still unpaginated in v1 (no page-number/cursor param), but no longer able to be
 * genuinely unbounded. {@code items} is most-recently-seen-first, and {@code truncated} is
 * {@code true} whenever the store held more rows than {@code inspector.incidents.list-cap} — the
 * server always drops the OLDEST rows by {@code lastSeen}, never the newest, so a truncated
 * response is still the freshest possible slice. Mirrors the
 * {@code RemediationDemandAnalysisService}'s scan-cap honesty pattern: an absent {@code window}
 * still means "the whole ledger" (the no-pagination-v1 decision stands), just "up to the cap"
 * now rather than truly unbounded — the frontend renders a badge whenever {@code truncated} is
 * {@code true} and never presents a capped list as if it were complete (ARCHITECTURE §2.3).
 */
public record IncidentListResponse(List<IncidentSummary> items, boolean truncated) {}
