package io.inspector.snapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Current window versus the median of the previous windows (#508). Fewer than three samples
 * has no reference yet — the strip says so instead of drawing a flat line.
 */
public final class ThroughputComparison {

    public record Windows(Long current, Long referenceMedian) {}

    private ThroughputComparison() {}

    /** {@code newestFirst} is the per-bucket counts, newest at index 0. A window is {@code width} buckets. */
    public static Windows of(List<Long> newestFirst, int width) {
        if (newestFirst == null || newestFirst.isEmpty() || width < 1) {
            return new Windows(null, null);
        }
        if (newestFirst.size() < width) {
            return new Windows(sum(newestFirst, 0, newestFirst.size()), null);
        }
        long current = sum(newestFirst, 0, width);
        List<Long> previous = new ArrayList<>();
        for (int start = width; start + width <= newestFirst.size() && previous.size() < 7; start += width) {
            previous.add(sum(newestFirst, start, start + width));
        }
        if (previous.isEmpty()) {
            return new Windows(current, null);
        }
        previous.sort(Long::compare);
        return new Windows(current, previous.get(previous.size() / 2));
    }

    private static long sum(List<Long> values, int from, int to) {
        long total = 0;
        for (int i = from; i < to; i++) {
            total += values.get(i);
        }
        return total;
    }
}
