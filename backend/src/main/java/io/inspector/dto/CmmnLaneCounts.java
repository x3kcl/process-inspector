package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The scope-typed status lanes of a co-deployed CMMN engine (Case Inspector Phase 1, R-SEM-20):
 * how many cases sit in each lifecycle state, mirroring the BPMN Stage-0 status counts but with
 * the CMMN lane set — {@code ACTIVE / FAILED / COMPLETED / TERMINATED}.
 *
 * <p><b>No SUSPENDED lane.</b> A CMMN case cannot be suspended ({@code {"action":"suspend"}} →
 * HTTP 400; spike Q2), so the BPMN {@code ALL_STATUSES} set (which hardcodes SUSPENDED and lacks
 * TERMINATED) is the mirror image of this one and must NOT be reused — the frontend drives its
 * lane set off a dedicated {@code CMMN_STATUSES} const (docs/CMMN-SCOPE-PHASE-0.md §7 M4 hazard).
 *
 * <p>Each count is nullable — {@code null} means "not known" (a per-lane query degraded), never a
 * misleading {@code 0}. {@code failed} is the number of DISTINCT cases carrying a dead-letter job
 * (from the same enumeration as {@link OutOfScopeDeadLetters}); when that scan was truncated the
 * value is a lower bound (see {@link OutOfScopeDeadLetters#truncated()}), rendered "≥N". The
 * {@code active}/{@code completed}/{@code terminated} counts are count-only ({@code size=1})
 * historic-case-instance queries per {@code ?state=} (iron rule: Stage-0 aggregations never fetch
 * rows). A dead-lettered case is still active, so {@code failed} overlaps {@code active} — exactly
 * as the BPMN FAILED count overlaps its running total.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CmmnLaneCounts(Integer active, Integer failed, Integer completed, Integer terminated) {}
