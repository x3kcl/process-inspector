package io.inspector.snapshot;

import java.time.Instant;

/**
 * Published synchronously by {@link SnapshotSampler} AFTER the snapshot-store write of each
 * cycle (ARCH §2.7): the event-decoupling seam that lets the incident ledger (and any future
 * consumer) ride the SAME aggregation pass without the sampler knowing it exists. The publish
 * is isolated in the sampler — a listener failure warns and never breaks the cycle — and
 * every listener additionally owns its own failure isolation.
 *
 * @param sample the full aggregation product (lane counts + error groups + truncation)
 * @param bucketedInstant {@code sample.sampledAt()} floored to the snapshot bucket grid — the
 *     idempotency key consumers should share so a re-fire within one bucket upserts, never
 *     double-counts
 */
public record AggregationSampledEvent(AggregationSample sample, Instant bucketedInstant) {}
