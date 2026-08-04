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
 *
 * <p>{@code cycleComplete} (V21) is the SECOND honesty marker, and it is read exactly like the
 * first: false means an engine was unreachable on the pass that wrote this row (#302), so the
 * counts may simply be missing that engine's members. A drop-and-recover across such a row is
 * NOT movement, and a {@code retryingCount} edge at it is NOT a spell boundary — see
 * {@link IncidentOccurrenceRepository#arrivalsSince} and
 * {@code io.inspector.selfheal.RetrySpellExtractor}.
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

    @Column(name = "cycle_complete", nullable = false)
    private boolean cycleComplete;

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

    /** False ⇒ a blind pass wrote this row (#302): unobserved, never "observed zero/absent". */
    public boolean isCycleComplete() {
        return cycleComplete;
    }
}
