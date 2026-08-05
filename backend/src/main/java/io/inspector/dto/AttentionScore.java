package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The cost-aware attention score for ONE error class (ALARM-COST-MODEL.md §4, #353) — the
 * ordinal shadow of the {@code B(c) = (1 - p_heal) · eff · c_miss - c_att} cost model. Because
 * {@code c_att} is identical across cards it cancels out of a RANKING, which is why the ordering
 * ships without any currency calibration (§3.1). {@code eff(c)} is deliberately NOT a v1 factor
 * (§3.1, panel fix): per-class effectiveness attribution is blocked for single-job verbs, so it
 * would collapse to a fleet-wide prior and discriminate nothing.
 *
 * <p><b>Absent unless {@code inspector.triage.attention-ordering} is on.</b> The R1 data-maturity
 * gate (ALARM-COST-MODEL §7) is measured NOT MET (0 of 5 axes), and on the recorded pilot history
 * the score is provably identical to today's count-only ordering (§5.5 — Kendall tau = 1.0 over
 * all 21,229 buckets, zero position changes). The feature therefore ships flag-off: with the flag
 * off nothing is computed, nothing is served, and the ordering is byte-for-byte today's.
 *
 * <p><b>Ordering only, never hides</b> (§4.1, R-BAU-01 verbatim): every live card still renders,
 * acknowledged groups keep their labeled never-hidden collapse and their auto-resurface
 * semantics, and no section membership changes. The score only sorts.
 *
 * <p>{@code rationale} is the ONE glanceable sentence explanation with this card's real numbers
 * (§4.3) — a hard requirement, computed server-side so every consumer renders the same sentence.
 * Rendered as visible page text on the card face since §12.1/issue #374 (previously hover-only
 * in a {@code title} tooltip). {@code suggestedAckExpirySeconds} is the §3.2 model-derived
 * ack-expiry SUGGESTION (class P75 closed-episode duration, fleet P75 fallback) — absent while
 * the class has too little
 * closed-episode history, which is today's behavior (no suggestion, UI selection {@code none}).
 * It is a suggestion only: the operator always overrides and the resurface guarantees are
 * untouched.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttentionScore(
        double score, AttentionFactors factors, String rationale, Long suggestedAckExpirySeconds) {}
