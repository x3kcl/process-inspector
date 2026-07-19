package io.inspector.snapshot;

/**
 * The ingestion seam (PLAN v2/M4 — "keep the door open"). The store and query tiers depend on
 * THIS interface, never on how a sample was produced. v2.0 ships one impl — {@link
 * PollingSnapshotSource}, which re-runs the Stage-0 count aggregation — so ingestion stays
 * strictly-via-REST (iron rule). A future event-driven source (Flowable history → broker) can
 * drop in per-engine, capability-gated, without touching {@link SnapshotCountRepository}, the
 * sampler's bucketing, or the trend UI.
 *
 * <p>v2/R-BAU-10 widened the return from bare lane counts to the whole {@link
 * AggregationSample} of ONE aggregation pass, so the incident ledger consumes the same
 * reading — one BACKGROUND-lane pass per cycle feeds both stores, zero extra engine calls
 * (ARCH §2.7).
 */
public interface SnapshotSource {

    /**
     * One point-in-time reading: every reachable engine's job lanes plus the pass's error
     * groups and truncation markers. Engines that are down (or cannot discriminate a lane,
     * e.g. pre-6.8 out-of-scope) contribute NO lane row — a gap in the series, never a
     * fabricated zero (honesty over completeness).
     */
    AggregationSample sample();
}
