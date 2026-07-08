package io.inspector.dto;

import java.util.List;

/**
 * The drill-down behind the Stage-0 {@code outOfScopeDeadletters} count (Case Inspector
 * Phase 1, R-SEM-20): the enumerated CMMN dead-letter jobs on ONE engine.
 *
 * <p>{@code truncated} carries the SAME honesty contract as the BPMN Stage-0 scan (iron rule:
 * "never a single unpaged DLQ fetch"): when the bounded, paged scan hit {@code dlq-scan-cap},
 * CMMN rows may lie past the cap, so {@code jobs} is a documented lower bound and the UI renders
 * "≥N" rather than an exact count. {@code scanned} is the number of dead-letter rows examined
 * (BPMN + CMMN — the shared table), for the ≥N narrative.
 *
 * <p>Phase 0 counts orphans from the PROCESS-api scan (null process-instance id); this Phase-1
 * enumeration reads the CMMN-api projection (non-null {@code caseInstanceId}). They are two
 * windows on the shared table with different caps, so the scalar count and this list can
 * legitimately disagree under truncation (docs/CMMN-SCOPE-PHASE-0.md §7). This is NOT an
 * "upgrade" of the Phase-0 scalar.
 */
public record OutOfScopeDeadLetters(List<CmmnDeadLetterJob> jobs, boolean truncated, int scanned) {}
