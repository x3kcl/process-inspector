package io.inspector.triage;

import io.inspector.dto.ErrorGroup;
import io.inspector.dto.ErrorGroupAcknowledgement;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The R-BAU-01 auto-resurface predicate — pure and rung-1 testable. Given one live error
 * group and its persisted ack slice rows (same {@code algoVersion} only — the caller
 * filters; a normalizer bump orphans old-generation acks by design), decides whether the
 * group stays collapsed or resurfaces, and why:
 *
 * <ol>
 *   <li><b>new-version</b> — a definition version (or a whole engine × definition slice)
 *       with FAILING members that the ack never covered. Zero-filled sibling versions do
 *       not count: a deployed-but-clean version is not a resurface signal.
 *   <li><b>grew</b> — the group's member count grew PAST the acknowledged baseline by the
 *       threshold (default +20%, {@code inspector.triage.ack-resurface-threshold-pct}).
 *   <li><b>expired</b> — the acknowledgment carried an expiry and it has passed.
 * </ol>
 *
 * The more specific signal wins the label (new-version &gt; grew &gt; expired);
 * {@code grownBy} always reports the raw delta so the UI can render "+n" regardless.
 */
public final class ErrorGroupAckPolicy {

    private ErrorGroupAckPolicy() {}

    /** One live engine × definition slice folded out of {@code countsByEngine}. */
    record Slice(long count, Integer maxFailingVersion) {}

    /**
     * @param rows this group's ack slice rows, already algoVersion-matched; empty → null
     * @return the overlay, or null when the group is simply unacknowledged
     */
    public static ErrorGroupAcknowledgement evaluate(
            ErrorGroup group, List<ErrorGroupAck> rows, int thresholdPct, Instant now) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        ErrorGroupAck latest = rows.stream()
                .max(Comparator.comparing(ErrorGroupAck::getAcknowledgedAt))
                .orElseThrow();
        long acknowledgedTotal =
                rows.stream().mapToLong(ErrorGroupAck::getAcknowledgedCount).sum();
        long grownBy = Math.max(0, group.total() - acknowledgedTotal);

        String reason = resurfaceReason(group, rows, acknowledgedTotal, thresholdPct, now);
        return new ErrorGroupAcknowledgement(
                latest.getAcknowledgedBy(),
                latest.getAcknowledgedAt().toString(),
                latest.getReason(),
                latest.getTicketId(),
                latest.getExpiresAt() != null ? latest.getExpiresAt().toString() : null,
                acknowledgedTotal,
                reason != null,
                reason,
                grownBy);
    }

    private static String resurfaceReason(
            ErrorGroup group, List<ErrorGroupAck> rows, long acknowledgedTotal, int thresholdPct, Instant now) {
        Map<SliceKey, ErrorGroupAck> ackBySlice = new LinkedHashMap<>();
        for (ErrorGroupAck row : rows) {
            ackBySlice.put(new SliceKey(row.getEngineId(), row.getDefinitionKey()), row);
        }
        for (Map.Entry<SliceKey, Slice> live : liveSlices(group).entrySet()) {
            ErrorGroupAck acked = ackBySlice.get(live.getKey());
            if (acked == null) {
                // Failing members on an engine × definition the ack never covered — new scope,
                // the strongest form of "a new definition version appeared".
                return "new-version";
            }
            Integer liveMax = live.getValue().maxFailingVersion();
            Integer ackedMax = acked.getAcknowledgedMaxVersion();
            if (liveMax != null && ackedMax != null && liveMax > ackedMax) {
                return "new-version";
            }
        }
        // Grows PAST the baseline by the threshold: strictly greater than baseline·(1+pct).
        if (group.total() * 100 > acknowledgedTotal * (100L + thresholdPct)) {
            return "grew";
        }
        for (ErrorGroupAck row : rows) {
            if (row.getExpiresAt() != null && !row.getExpiresAt().isAfter(now)) {
                return "expired";
            }
        }
        return null;
    }

    /**
     * Folds {@code countsByEngine} ({@code engineId → "defKey:vN" → count}) into per
     * engine × definition slices, keeping only slices with FAILING members (zero-filled
     * versions inform nothing here). Used by the ack door for baselines and above for the
     * live comparison — one parse, one contract.
     */
    static Map<SliceKey, Slice> liveSlices(ErrorGroup group) {
        Map<SliceKey, Slice> slices = new LinkedHashMap<>();
        Map<String, Map<String, Long>> byEngine = group.countsByEngine() != null ? group.countsByEngine() : Map.of();
        for (Map.Entry<String, Map<String, Long>> engine : byEngine.entrySet()) {
            for (Map.Entry<String, Long> defVersion : engine.getValue().entrySet()) {
                long count = defVersion.getValue() != null ? defVersion.getValue() : 0;
                if (count == 0) {
                    continue;
                }
                ParsedKey parsed = parseDefVersionKey(defVersion.getKey());
                SliceKey key = new SliceKey(engine.getKey(), parsed.definitionKey());
                Slice previous = slices.get(key);
                Integer maxVersion =
                        maxVersion(previous != null ? previous.maxFailingVersion() : null, parsed.version());
                long total = (previous != null ? previous.count() : 0) + count;
                slices.put(key, new Slice(total, maxVersion));
            }
        }
        return slices;
    }

    record SliceKey(String engineId, String definitionKey) {}

    private record ParsedKey(String definitionKey, Integer version) {}

    /** Splits the aggregation's inner key {@code "defKey:vN"}; unparsable versions → null. */
    private static ParsedKey parseDefVersionKey(String key) {
        int at = key.lastIndexOf(':');
        if (at > 0 && key.length() > at + 1 && key.charAt(at + 1) == 'v') {
            String suffix = key.substring(at + 2);
            if (!suffix.isEmpty() && suffix.chars().allMatch(Character::isDigit)) {
                try {
                    return new ParsedKey(key.substring(0, at), Integer.parseInt(suffix));
                } catch (NumberFormatException e) {
                    return new ParsedKey(key.substring(0, at), null);
                }
            }
            return new ParsedKey(key.substring(0, at), null);
        }
        return new ParsedKey(key, null);
    }

    private static Integer maxVersion(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Math.max(a, b);
    }
}
