package io.inspector.incident;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * The composite business key of {@code incident_occurrence} (INCIDENT-LEDGER.md §3.3): the PK
 * IS {@code (incident_id, sampled_at)} — no surrogate id, because one would not be unique
 * across range partitions and invites unsafe id-only references (panel: o3 BLOCKER fix).
 * A plain {@code @Embeddable} class (not a record) to stay on the JPA-spec-guaranteed path.
 */
@Embeddable
public class IncidentOccurrenceId implements Serializable {

    @Column(name = "incident_id", nullable = false)
    private long incidentId;

    /** The bucket-floored observation instant (the upsert idempotency key). */
    @Column(name = "sampled_at", nullable = false)
    private Instant sampledAt;

    protected IncidentOccurrenceId() {
        // JPA
    }

    public IncidentOccurrenceId(long incidentId, Instant sampledAt) {
        this.incidentId = incidentId;
        this.sampledAt = sampledAt;
    }

    public long getIncidentId() {
        return incidentId;
    }

    public Instant getSampledAt() {
        return sampledAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IncidentOccurrenceId other)) {
            return false;
        }
        return incidentId == other.incidentId && Objects.equals(sampledAt, other.sampledAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(incidentId, sampledAt);
    }
}
