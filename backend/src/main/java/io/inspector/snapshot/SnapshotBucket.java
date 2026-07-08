package io.inspector.snapshot;

import java.time.Duration;
import java.time.Instant;

/**
 * Floors a sampling instant to its bucket start — the idempotency key for the snapshot upsert
 * (V5__triage_snapshot.sql, PLAN v2/M4). Two sampler fires that land in the same bucket (a
 * scheduler overlap, or a restart re-firing {@code ApplicationReadyEvent} within one bucket)
 * upsert the SAME {@code (engine, lane, sampled_at)} row instead of double-counting.
 *
 * <p>Pure + total: floors on the epoch-second grid so buckets are stable across restarts and
 * independent of wall-clock offset. The bucket width should be ≥ the sampler cadence so a
 * normal (non-overlapping) fire always advances to a fresh bucket.
 */
public final class SnapshotBucket {

    private final long bucketSeconds;

    public SnapshotBucket(Duration bucketWidth) {
        long seconds = bucketWidth.getSeconds();
        if (seconds <= 0) {
            throw new IllegalArgumentException("bucket width must be positive: " + bucketWidth);
        }
        this.bucketSeconds = seconds;
    }

    /** The start instant of the bucket containing {@code sampledAt} (truncated toward the epoch). */
    public Instant floor(Instant sampledAt) {
        long epoch = sampledAt.getEpochSecond();
        long floored = Math.floorDiv(epoch, bucketSeconds) * bucketSeconds;
        return Instant.ofEpochSecond(floored);
    }
}
