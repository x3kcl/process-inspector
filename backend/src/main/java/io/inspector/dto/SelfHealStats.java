package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The per-class self-heal statistic (RETRYING-RISK-LANE.md §3.2/§10, #351) embedded on
 * {@link IncidentSummary} (list items AND the detail, which embeds the summary verbatim).
 *
 * <p>{@code lane} is the SERVER-dwelled DISPLAYED lane — never the raw one
 * (RETRYING-RISK-LANE.md §4.2 rule 3: a refresh must never reset stability state, and two
 * operators must see the same lane for the same class) — one of {@code SELF_HEAL_LIKELY},
 * {@code SELF_HEAL_MIXED}, {@code SELF_HEAL_UNLIKELY}, {@code INSUFFICIENT_HISTORY}.
 * {@code INSUFFICIENT_HISTORY} is the NORMAL case, not an edge case (measured 2026-08-04,
 * docs/reviews/R2-SELFHEAL-BASELINE-2026-08.md: zero unconfounded completed spells exist
 * anywhere in the pilot ledger today).
 *
 * <p>{@code n}/{@code healed} are always present. {@code wilsonLow}/{@code wilsonHigh}/
 * {@code ttsP50Seconds}/{@code ttsP90Seconds} are absent below the sample-size floor
 * (n &lt; 10 per §7.1) — the DTO cannot express a sub-floor rate (panel G18) — and the
 * {@code tts*} pair is additionally absent whenever {@code healed = 0} (no self-heal has ever
 * completed, so no duration distribution exists to summarize). {@code excludedSpells} counts
 * confounded/gap-voided/truncation-tainted spells dropped from {@code n} (never silently,
 * §3.1); {@code truncationTainted} is the R-SEM-12 propagation flag (true iff at least one
 * excluded spell in the window was excluded specifically because of truncation).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SelfHealStats(
        String lane,
        int n,
        int healed,
        Double wilsonLow,
        Double wilsonHigh,
        Long ttsP50Seconds,
        Long ttsP90Seconds,
        int excludedSpells,
        boolean truncationTainted) {}
