package io.inspector.triage.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.triage.ErrorSignatureNormalizer;
import io.inspector.triage.quality.GroupingQualityMetrics.LabeledEntry;
import io.inspector.triage.quality.GroupingQualityMetrics.Result;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Issue #350 (R4) — the grouping-quality baseline for {@code ErrorSignatureNormalizer} algo
 * v2, run against the hand-labeled corpus captured by
 * {@code scripts/harvest-grouping-quality-corpus.py}
 * ({@code backend/src/test/resources/grouping-quality/corpus.json}). MEASUREMENT ONLY: this
 * class does not gate merges to the normalizer (that is
 * {@code ErrorSignatureGoldenCorpusTest}'s job) — it exists to prove or disprove, with real
 * numbers, whether v2 has a material grouping-quality deficit before anyone is allowed to
 * propose a v3 algorithm (Drain-style template mining or dedupT-style similarity linking).
 *
 * <p><b>Material-deficit thresholds — fixed BEFORE this test was ever run against real
 * numbers</b> (see docs/reviews/R4-GROUPING-QUALITY-2026-08.md §"Threshold, set in
 * advance"):
 *
 * <ul>
 *   <li><b>Under-grouping ≤ 5% (entry-weighted).</b> Strict, because eliminating exactly
 *       this defect class was the entire point of #270/algo v2 — any material rate here
 *       would mean the v2 fix regressed or never fully worked.
 *   <li><b>Over-grouping ≤ 20% (entry-weighted).</b> Looser, because
 *       {@link ErrorSignatureNormalizer}'s own doc comment already documents over-grouping
 *       (two causes sharing one wrapper message) as an ACCEPTED, deliberate cost of the v2
 *       design — this harness exists to confirm that cost stays bounded on a real corpus,
 *       not to demand zero over-grouping (that would require the v3 candidate algorithms
 *       themselves).
 * </ul>
 *
 * If either threshold is breached, file the v3 issue with these numbers as evidence. If
 * not — per issue #350 — this track ends here, and that is the successful outcome.
 */
class GroupingQualityBaselineTest {

    private static final double UNDER_GROUPING_MATERIAL_DEFICIT_THRESHOLD = 0.05;
    private static final double OVER_GROUPING_MATERIAL_DEFICIT_THRESHOLD = 0.20;

    private record Entry(String groupId, String exceptionMessage) {}

    private static List<Entry> readEntries(JsonNode arrayNode) {
        List<Entry> out = new ArrayList<>();
        arrayNode.forEach(e -> out.add(
                new Entry(e.get("groupId").asText(), e.get("exceptionMessage").asText())));
        return out;
    }

    private static JsonNode corpus() throws Exception {
        return new ObjectMapper()
                .readTree(GroupingQualityBaselineTest.class.getResourceAsStream("/grouping-quality/corpus.json"));
    }

    private static List<LabeledEntry> labelWithCurrentNormalizer(List<Entry> entries) {
        List<LabeledEntry> labeled = new ArrayList<>();
        for (Entry e : entries) {
            String hash =
                    ErrorSignatureNormalizer.normalize(e.exceptionMessage()).hash();
            labeled.add(new LabeledEntry(e.groupId(), hash));
        }
        return labeled;
    }

    private static void printReport(String label, Result r) {
        System.out.printf(
                "[R4 grouping-quality] %s algoVersion=%d entries=%d trueGroups=%d predictedClusters=%d%n"
                        + "  over-grouping:  cluster-weighted=%.1f%% (%d/%d impure clusters)  entry-weighted=%.1f%%%n"
                        + "  under-grouping: group-weighted=%.1f%% (%d/%d split groups)  entry-weighted=%.1f%%%n",
                label,
                ErrorSignatureNormalizer.ALGO_VERSION,
                r.totalEntries(),
                r.trueGroupCount(),
                r.predictedClusterCount(),
                r.overGroupingRateClusterWeighted() * 100,
                r.impureClusterCount(),
                r.predictedClusterCount(),
                r.overGroupingRateEntryWeighted() * 100,
                r.underGroupingRateGroupWeighted() * 100,
                r.splitGroupCount(),
                r.trueGroupCount(),
                r.underGroupingRateEntryWeighted() * 100);
    }

    /**
     * THE baseline: the primary, organically-harvested corpus (real dead-letter/timer
     * payloads from the dockerized engines + the ACME suite's one organic failure + two
     * confirmed rows from the live demo) — five genuinely distinct, precisely-known root
     * causes. No engineered near-collisions here; see
     * {@link #adversarialPairsPinTheMeasuredCollisionAndNearMissCaseStudies()} for that.
     */
    @Test
    void algoV2StaysWithinMaterialDeficitThresholdsOnTheOrganicCorpus() throws Exception {
        JsonNode corpus = corpus();
        List<Entry> entries = readEntries(corpus.get("entries"));
        assertThat(entries)
                .as("organic corpus floor — below this the metrics are too noisy to trust")
                .hasSizeGreaterThanOrEqualTo(20);
        assertThat(entries.stream().map(Entry::groupId).distinct().count())
                .as("the corpus must cover more than one true cause, or over-grouping is untestable")
                .isGreaterThanOrEqualTo(4);

        Result result = GroupingQualityMetrics.evaluate(labelWithCurrentNormalizer(entries));
        printReport("PRIMARY (organic corpus)", result);

        assertThat(result.underGroupingRateEntryWeighted())
                .as("under-grouping (one true cause split across signatures) — the exact #270 defect "
                        + "class — must stay at or below the threshold fixed BEFORE this measurement")
                .isLessThanOrEqualTo(UNDER_GROUPING_MATERIAL_DEFICIT_THRESHOLD);
        assertThat(result.overGroupingRateEntryWeighted())
                .as("over-grouping (distinct true causes fused into one signature) must stay at or below "
                        + "the threshold fixed BEFORE this measurement — some over-grouping is an "
                        + "accepted v2 design cost, this just bounds it")
                .isLessThanOrEqualTo(OVER_GROUPING_MATERIAL_DEFICIT_THRESHOLD);
    }

    /**
     * NOT a pass/fail gate on the material-deficit thresholds (deliberately excluded from
     * those — see the corpus's own {@code note} field and the harvest script's docstring for
     * why hand-picked pairs must not dominate an organic-corpus metric). This test instead
     * PINS two known, hand-picked case studies side by side:
     *
     * <ul>
     *   <li><b>missing-property / missing-property-variant</b> ('ghost' vs 'phantom' — two
     *       different missing upstream data dependencies): a HYPOTHESIZED over-grouping risk
     *       via PropertyNotFoundException's quoted-literal sanitization, MEASURED to NOT
     *       collide — v2 identity hashes the job's own exceptionMessage snippet, which still
     *       embeds the literal expression text (only the display-only stacktrace refinement
     *       quotes it, and refinement never re-keys per #270).
     *   <li><b>acme-billing-outage / acme-shipping-outage</b> (billing vendor down vs
     *       shipping vendor down — two different downstream integrations): DOES collide —
     *       Flowable's HTTP-task connector's job-level exceptionMessage is the generic
     *       constant {@code "execution exception"} regardless of which host failed. This is
     *       exactly the cost {@link ErrorSignatureNormalizer}'s own doc comment calls out as
     *       deliberately accepted.
     * </ul>
     *
     * This test exists so a future normalizer change that shifts either behavior shows up
     * here as a visible diff, not a silent drift.
     */
    @Test
    void adversarialPairsPinTheMeasuredCollisionAndNearMissCaseStudies() throws Exception {
        JsonNode corpus = corpus();
        List<Entry> entries = readEntries(corpus.get("adversarialEntries"));
        assertThat(entries).as("adversarial pair fixtures must be present").isNotEmpty();

        Result result = GroupingQualityMetrics.evaluate(labelWithCurrentNormalizer(entries));
        printReport("ADVERSARIAL (hand-picked case studies, excluded from the verdict)", result);

        assertThat(result.trueGroupCount()).as("the four hand-picked groups").isEqualTo(4);
        assertThat(result.splitGroupCount())
                .as("no individual group is itself split")
                .isEqualTo(0);
        assertThat(result.impureClusterCount())
                .as("exactly one impure cluster — the ACME billing/shipping fusion")
                .isEqualTo(1);

        String missingPropertyHash = hashOf(entries, "missing-property");
        String missingPropertyVariantHash = hashOf(entries, "missing-property-variant");
        String billingHash = hashOf(entries, "acme-billing-outage");
        String shippingHash = hashOf(entries, "acme-shipping-outage");

        assertThat(missingPropertyHash)
                .as("missing-property vs its variant — the NEAR-MISS: two distinct causes, NOT fused")
                .isNotEqualTo(missingPropertyVariantHash);
        assertThat(billingHash)
                .as("ACME billing vs shipping outage — the CONFIRMED collision: two distinct causes, fused")
                .isEqualTo(shippingHash);
    }

    private static String hashOf(List<Entry> entries, String groupId) {
        return entries.stream()
                .filter(e -> e.groupId().equals(groupId))
                .findFirst()
                .map(e ->
                        ErrorSignatureNormalizer.normalize(e.exceptionMessage()).hash())
                .orElseThrow(() -> new IllegalStateException("no entry for groupId " + groupId));
    }
}
