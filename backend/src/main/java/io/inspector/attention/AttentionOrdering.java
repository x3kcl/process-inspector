package io.inspector.attention;

import io.inspector.dto.AttentionScore;
import io.inspector.dto.ErrorGroup;
import java.util.Comparator;
import java.util.List;

/**
 * The Stage-0 card order under the attention model (ALARM-COST-MODEL.md §4.1, #353).
 *
 * <p><b>Ordering only.</b> No card is filtered, no section membership changes, acknowledged
 * groups keep their labeled never-hidden collapse and their auto-resurface triggers verbatim
 * (R-BAU-01, §9). This class returns a permutation of its input — same elements, same count.
 *
 * <p>The comparator is a TOTAL order (R-SEM-23): score DESC, then the count-only key
 * {@code total DESC}, then {@code signatureHash ASC}. The tie-break is load-bearing rather than
 * cosmetic — with no ledger history every score is 0.0, so the comparison falls straight through
 * to {@code total DESC} and the result is byte-for-byte today's ordering (§4.1, measured §5.5).
 * A missing score sorts as 0.0 for the same reason: an unscored card is never buried.
 */
public final class AttentionOrdering {

    /** Score DESC → total DESC → signatureHash ASC. Deterministic for identical inputs. */
    public static final Comparator<ErrorGroup> BY_ATTENTION = Comparator.comparingDouble(AttentionOrdering::scoreOf)
            .reversed()
            .thenComparing(Comparator.comparingLong(ErrorGroup::total).reversed())
            .thenComparing(group -> group.signatureHash() != null ? group.signatureHash() : "");

    private AttentionOrdering() {}

    public static List<ErrorGroup> order(List<ErrorGroup> groups) {
        return groups.stream().sorted(BY_ATTENTION).toList();
    }

    private static double scoreOf(ErrorGroup group) {
        AttentionScore attention = group.attention();
        return attention != null ? attention.score() : 0.0;
    }
}
