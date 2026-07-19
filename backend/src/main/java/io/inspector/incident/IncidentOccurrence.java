package io.inspector.incident;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * One narrow time-series point of an incident (V18, INCIDENT-LEDGER.md §3.3) — the
 * sparkline/timeline substrate. Writes go through {@link IncidentOccurrenceRepository#upsert}
 * (native idempotent {@code ON CONFLICT}, mirroring {@code SnapshotCountRepository} — a poll is
 * not a mutation, no {@code EntityManager.persist}); this entity is the READ projection.
 *
 * <p>{@code truncated} keeps the series honest end-to-end (R-SEM-12): a truncated sample is a
 * FLOOR, not a dip — the UI renders such points visually distinct.
 */
@Entity
@Table(name = "incident_occurrence")
public class IncidentOccurrence {

    @EmbeddedId
    private IncidentOccurrenceId id;

    @Column(name = "total", nullable = false)
    private long total;

    @Column(name = "dead_letter_count", nullable = false)
    private long deadLetterCount;

    @Column(name = "retrying_count", nullable = false)
    private long retryingCount;

    @Column(name = "truncated", nullable = false)
    private boolean truncated;

    protected IncidentOccurrence() {
        // JPA
    }

    public IncidentOccurrenceId getId() {
        return id;
    }

    public long getTotal() {
        return total;
    }

    public long getDeadLetterCount() {
        return deadLetterCount;
    }

    public long getRetryingCount() {
        return retryingCount;
    }

    public boolean isTruncated() {
        return truncated;
    }
}
