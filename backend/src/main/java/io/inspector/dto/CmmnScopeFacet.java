package io.inspector.dto;

/**
 * The Case Inspector's scope-typed view of a co-deployed CMMN engine on ONE engine (Phase 1,
 * R-SEM-20): the status {@link CmmnLaneCounts lane counts} plus the enumerated FAILED-lane detail
 * ({@link OutOfScopeDeadLetters}, the dead-letter jobs the BPMN status join excludes).
 *
 * <p>This promotes the drawer from "just the out-of-scope dead-letter jobs" (Phase-1 first slice)
 * to a real case-scoped view: an operator sees HOW MANY cases are active / failing / completed /
 * terminated, then drills the FAILED lane into the specific dead-letter jobs. Still read-only —
 * CMMN corrective actions are Phase 3. Gated 6.8+ in the BFF (a pre-6.8 cmmn context is
 * dead-letter-blind and ignores {@code ?state=}; spike Q3) — never a silently wrong view.
 */
public record CmmnScopeFacet(CmmnLaneCounts lanes, OutOfScopeDeadLetters deadletters) {}
