package io.inspector.snapshot;

import java.util.List;

/**
 * The ingestion seam (PLAN v2/M4 — "keep the door open"). The store and query tiers depend on
 * THIS interface, never on how a sample was produced. v2.0 ships one impl — {@link
 * PollingSnapshotSource}, which re-runs the Stage-0 count aggregation — so ingestion stays
 * strictly-via-REST (iron rule). A future event-driven source (Flowable history → broker) can
 * drop in per-engine, capability-gated, without touching {@link SnapshotCountRepository}, the
 * sampler's bucketing, or the trend UI.
 */
public interface SnapshotSource {

    /**
     * One point-in-time reading of every reachable engine's job lanes. Engines that are down (or
     * cannot discriminate a lane, e.g. pre-6.8 out-of-scope) contribute NO row for that lane — a
     * gap in the series, never a fabricated zero (honesty over completeness).
     */
    List<EngineLaneCount> sample();
}
