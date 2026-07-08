package io.inspector.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Rung 1: the idempotency-key flooring is pure arithmetic on the epoch-second grid. */
class SnapshotBucketTest {

    private final SnapshotBucket bucket = new SnapshotBucket(Duration.ofSeconds(60));

    @Test
    void twoInstantsInsideOneWidthFloorToTheSameBucket() {
        Instant a = Instant.parse("2026-07-08T12:00:03Z");
        Instant b = Instant.parse("2026-07-08T12:00:59Z");
        assertThat(bucket.floor(a)).isEqualTo(bucket.floor(b)).isEqualTo(Instant.parse("2026-07-08T12:00:00Z"));
    }

    @Test
    void crossingTheWidthAdvancesToTheNextBucket() {
        assertThat(bucket.floor(Instant.parse("2026-07-08T12:00:59Z")))
                .isNotEqualTo(bucket.floor(Instant.parse("2026-07-08T12:01:00Z")));
        assertThat(bucket.floor(Instant.parse("2026-07-08T12:01:00Z")))
                .isEqualTo(Instant.parse("2026-07-08T12:01:00Z"));
    }

    @Test
    void bucketsAreStableAcrossRestartsRegardlessOfWallClockOffset() {
        // The grid is anchored on the epoch, not on process start — a restart mid-bucket
        // re-derives the same bucket, so the upsert dedupes instead of double-counting.
        SnapshotBucket fiveMin = new SnapshotBucket(Duration.ofMinutes(5));
        assertThat(fiveMin.floor(Instant.parse("2026-07-08T12:07:30Z")))
                .isEqualTo(Instant.parse("2026-07-08T12:05:00Z"));
    }

    @Test
    void rejectsNonPositiveWidth() {
        assertThatThrownBy(() -> new SnapshotBucket(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
