package io.inspector.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.Snapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Rung 1: bucketing + idempotent write + do-no-harm degradation, all with mocked collaborators —
 * plus the R-BAU-10 event seam: the cycle's {@link AggregationSample} is published AFTER the
 * store write, and a listener failure can never break the cycle or its return value.
 */
class SnapshotSamplerTest {

    private static final Instant NOW = Instant.parse("2026-07-08T12:00:37Z");
    private static final Instant BUCKET = Instant.parse("2026-07-08T12:00:00Z");

    private final SnapshotSource source = mock(SnapshotSource.class);
    private final SnapshotCountRepository repository = mock(SnapshotCountRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SnapshotSampler sampler = new SnapshotSampler(
            source, repository, props(Duration.ofSeconds(60)), Clock.fixed(NOW, ZoneOffset.UTC), events);

    @Test
    void upsertsEachObservationAtTheFlooredBucket() {
        when(source.sample())
                .thenReturn(sample(
                        new EngineLaneCount("engine-a", SnapshotLane.ACTIVE, 5),
                        new EngineLaneCount("engine-a", SnapshotLane.OUT_OF_SCOPE_DLQ, 3)));

        int written = sampler.sampleOnce();

        assertThat(written).isEqualTo(2);
        verify(repository).upsert("engine-a", "ACTIVE", 5, BUCKET);
        verify(repository).upsert("engine-a", "OUT_OF_SCOPE_DLQ", 3, BUCKET);
    }

    @Test
    void publishesTheWholeSampleAtTheBucketAfterTheStoreWrite() {
        AggregationSample sample = sample(new EngineLaneCount("engine-a", SnapshotLane.ACTIVE, 5));
        when(source.sample()).thenReturn(sample);

        sampler.sampleOnce();

        verify(events).publishEvent(new AggregationSampledEvent(sample, BUCKET));
    }

    @Test
    void listenerFailureNeverBreaksTheCycleOrItsReturnValue() {
        when(source.sample()).thenReturn(sample(new EngineLaneCount("engine-a", SnapshotLane.ACTIVE, 5)));
        doThrow(new RuntimeException("ledger exploded")).when(events).publishEvent(any(Object.class));

        assertThat(sampler.sampleOnce()).isEqualTo(1); // no exception escapes, count unchanged
    }

    @Test
    void storeUnavailableDegradesToASkippedCycle() {
        when(source.sample()).thenReturn(sample(new EngineLaneCount("engine-a", SnapshotLane.ACTIVE, 1)));
        when(repository.upsert(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("db down"));

        assertThat(sampler.sampleOnce()).isZero(); // no exception escapes
        // a cycle whose store write failed is not an observed cycle — no event either
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void aggregationFailureNeverTouchesTheStoreNorPublishes() {
        when(source.sample()).thenThrow(new RuntimeException("all engines down"));

        assertThat(sampler.sampleOnce()).isZero();
        verify(repository, never())
                .upsert(
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    private static AggregationSample sample(EngineLaneCount... laneCounts) {
        return new AggregationSample(List.of(laneCounts), List.of(), NOW, Set.of());
    }

    private static InspectorProperties props(Duration bucketWidth) {
        return new InspectorProperties(
                null, null, null, null, new Snapshot(true, Duration.ofSeconds(60), bucketWidth, 400), List.of());
    }
}
