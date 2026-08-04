package io.inspector.attention;

import java.util.ArrayList;
import java.util.List;

/**
 * Nearest-rank quantiles over a small sample (ALARM-COST-MODEL.md §3.2/§4.1) — the SAME
 * discrete estimator {@code SelfHealStatsComputer} uses for its time-to-self-heal percentiles, so
 * "P75" means one thing across both research tracks. No interpolation: with the sample sizes
 * these estimators are gated at (3 closed episodes, 10 spells) an interpolated quantile invents
 * precision the data does not carry.
 */
public final class Quantiles {

    private Quantiles() {}

    /** Nearest-rank percentile ({@code p} in [0,1]); {@code null} for an empty sample. */
    public static Long percentile(List<Long> sample, double p) {
        if (sample == null || sample.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(sample);
        sorted.sort(null);
        int rank = (int) Math.ceil(p * sorted.size());
        int index = Math.max(0, Math.min(sorted.size() - 1, rank - 1));
        return sorted.get(index);
    }

    /** The median (P50) — the M factor's estimator, per class and fleet-wide. */
    public static Long median(List<Long> sample) {
        return percentile(sample, 0.50);
    }
}
