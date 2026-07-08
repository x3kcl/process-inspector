package io.inspector.snapshot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One narrow time-series row: {@code (engine, lane) → count} at a bucketed instant. Maps EXACTLY
 * onto the Flyway-authored {@code triage_snapshot} table (V5__triage_snapshot.sql) — the schema
 * is the truth, this class follows it (M4 build-order constraint), {@code ddl-auto=validate}.
 *
 * <p>Writes go through {@link SnapshotCountRepository#upsert} (a native idempotent
 * {@code ON CONFLICT} upsert — a poll is not a mutation, no {@code EntityManager.persist}); this
 * entity is the READ projection for the trend query. {@code id} is DB-assigned (sequence default),
 * so it is read-only from Java (matching {@code audit_entry.seq}).
 */
@Entity
@Table(name = "triage_snapshot")
public class SnapshotCount {

    @Id
    @Column(name = "id", insertable = false, updatable = false)
    private Long id;

    @Column(name = "engine_id", nullable = false)
    private String engineId;

    @Enumerated(EnumType.STRING)
    @Column(name = "lane", nullable = false)
    private SnapshotLane lane;

    @Column(name = "count", nullable = false)
    private long count;

    @Column(name = "sampled_at", nullable = false)
    private Instant sampledAt;

    protected SnapshotCount() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public String getEngineId() {
        return engineId;
    }

    public SnapshotLane getLane() {
        return lane;
    }

    public long getCount() {
        return count;
    }

    public Instant getSampledAt() {
        return sampledAt;
    }
}
