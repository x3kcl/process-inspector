package io.inspector.snapshot;

/**
 * One (engine, lane) → count observation produced by a {@link SnapshotSource}, before it is
 * bucketed and upserted into the time-series. Deliberately un-timestamped: the sampler stamps
 * the bucket at write time so every lane of one sample shares one bucket instant.
 */
public record EngineLaneCount(String engineId, SnapshotLane lane, long count) {}
