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
 *
 * <p>{@code fleet} (V22, #372) is a different kind of marker altogether: the two above are
 * observation QUALITY ("how well did we see what we were looking at"), this one is observation
 * SCOPE ("what were we looking at"). It carries the canonical sorted id set of the ENABLED
 * engines the writing pass fanned out over. A registry disable/enable changes the scope without
 * touching either quality marker — the pass is honestly complete for its new, smaller fleet — so
 * two rows can both be {@code cycleComplete} and still be non-comparable levels. Difference two
 * rows only when both carry the SAME non-empty fleet; {@code ""} means scope was never recorded
 * and is comparable to nothing, itself included.
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

    @Column(name = "fleet", nullable = false)
    private String fleet;

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

    /**
     * The row's observation SCOPE (V22, #372) — canonical sorted comma-joined enabled-engine ids.
     * {@code ""} = unrecorded, which compares equal to NOTHING (not even to another {@code ""}).
     */
    public String getFleet() {
        return fleet != null ? fleet : "";
    }
}
