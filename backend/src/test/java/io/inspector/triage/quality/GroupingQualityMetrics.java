package io.inspector.triage.quality;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Drain / dedupT-style grouping-quality metrics for issue #350 (R4): given a labeled
 * corpus (each entry carries a hand-labeled ground-truth {@code groupId} — the TRUE root
 * cause — and a {@code predictedKey} — what the normalizer under test actually hashed it
 * to), compute the two failure classes the issue asks for:
 *
 * <ul>
 *   <li><b>Over-grouping</b> — distinct TRUE causes fused into one predicted signature. A
 *       predicted cluster is "impure" if it contains entries from more than one groupId.</li>
 *   <li><b>Under-grouping</b> — one TRUE cause split across more than one predicted
 *       signature (the exact defect class algo v2 fixed for #270). A true group is "split"
 *       if its entries land on more than one predicted key.</li>
 * </ul>
 *
 * Both are reported two ways: <b>group/cluster-weighted</b> (does the defect happen at all,
 * regardless of how many entries it touches) and <b>entry-weighted</b> (how much of the
 * corpus does the defect actually touch) — the entry-weighted figure is the one findings
 * reports should quote, since a rare defect touching a huge cluster matters more than one
 * touching a handful of entries, and vice versa.
 *
 * <p>Pure, static, zero dependencies beyond the JDK — rung 1 of the unit-test-patterns
 * ladder. Takes an already-computed {@code predictedKey} (never calls the normalizer
 * itself) so it stays reusable for ANY future candidate algorithm's output, not just the
 * current one.
 */
public final class GroupingQualityMetrics {

    private GroupingQualityMetrics() {}

    /** One corpus row already run through whatever normalizer is under measurement. */
    public record LabeledEntry(String groupId, String predictedKey) {}

    public record Result(
            int totalEntries,
            int trueGroupCount,
            int predictedClusterCount,
            int impureClusterCount,
            int splitGroupCount,
            double overGroupingRateClusterWeighted,
            double overGroupingRateEntryWeighted,
            double underGroupingRateGroupWeighted,
            double underGroupingRateEntryWeighted) {}

    public static Result evaluate(List<LabeledEntry> entries) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("cannot evaluate grouping quality over an empty corpus");
        }

        Map<String, List<LabeledEntry>> byPredictedKey = new HashMap<>();
        Map<String, List<LabeledEntry>> byTrueGroup = new HashMap<>();
        for (LabeledEntry e : entries) {
            byPredictedKey
                    .computeIfAbsent(e.predictedKey(), k -> new java.util.ArrayList<>())
                    .add(e);
            byTrueGroup
                    .computeIfAbsent(e.groupId(), k -> new java.util.ArrayList<>())
                    .add(e);
        }

        int impureClusters = 0;
        int entriesInImpureClusters = 0;
        for (List<LabeledEntry> cluster : byPredictedKey.values()) {
            Set<String> distinctTrueGroups = distinctGroupIds(cluster);
            if (distinctTrueGroups.size() > 1) {
                impureClusters++;
                entriesInImpureClusters += cluster.size();
            }
        }

        int splitGroups = 0;
        int entriesInSplitGroups = 0;
        for (List<LabeledEntry> group : byTrueGroup.values()) {
            Set<String> distinctPredictedKeys = distinctPredictedKeys(group);
            if (distinctPredictedKeys.size() > 1) {
                splitGroups++;
                entriesInSplitGroups += group.size();
            }
        }

        return new Result(
                entries.size(),
                byTrueGroup.size(),
                byPredictedKey.size(),
                impureClusters,
                splitGroups,
                rate(impureClusters, byPredictedKey.size()),
                rate(entriesInImpureClusters, entries.size()),
                rate(splitGroups, byTrueGroup.size()),
                rate(entriesInSplitGroups, entries.size()));
    }

    private static Set<String> distinctGroupIds(Collection<LabeledEntry> entries) {
        Set<String> s = new HashSet<>();
        for (LabeledEntry e : entries) {
            s.add(e.groupId());
        }
        return s;
    }

    private static Set<String> distinctPredictedKeys(Collection<LabeledEntry> entries) {
        Set<String> s = new HashSet<>();
        for (LabeledEntry e : entries) {
            s.add(e.predictedKey());
        }
        return s;
    }

    private static double rate(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }
}
