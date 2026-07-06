package io.inspector.bulk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A persisted tracked bulk job (SPEC §7, R-SEM-10). State machine:
 * {@code PENDING → RUNNING → (COMPLETED | CANCELLED | INTERRUPTED)} — enforced by the
 * Flyway-authored CHECK constraint (V2__bulk_job.sql); this entity aligns to it.
 */
@Entity
@Table(name = "bulk_job")
public class BulkJob {

    /** SPEC §7 v1: grid-selection bulk is capped at 200 items. */
    public static final int ITEM_CAP = 200;

    public enum State {
        PENDING,
        RUNNING,
        COMPLETED,
        CANCELLED,
        INTERRUPTED
    }

    @Id
    private UUID id;

    @Column(name = "submitted_by", nullable = false)
    private String submittedBy;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "verb", nullable = false)
    private String verb;

    @Column(name = "reason")
    private String reason;

    @Column(name = "ticket_id")
    private String ticketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private State state;

    @Column(name = "total_items", nullable = false)
    private int totalItems;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "continued_from")
    private UUID continuedFrom;

    protected BulkJob() {
        // JPA
    }

    public BulkJob(
            UUID id,
            String submittedBy,
            Instant submittedAt,
            String verb,
            String reason,
            String ticketId,
            int totalItems,
            UUID continuedFrom) {
        this.id = id;
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
        this.verb = verb;
        this.reason = reason;
        this.ticketId = ticketId;
        this.state = State.PENDING;
        this.totalItems = totalItems;
        this.continuedFrom = continuedFrom;
    }

    public void markRunning() {
        this.state = State.RUNNING;
    }

    public void finish(State terminal, Instant at) {
        this.state = terminal;
        this.finishedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public String getVerb() {
        return verb;
    }

    public String getReason() {
        return reason;
    }

    public String getTicketId() {
        return ticketId;
    }

    public State getState() {
        return state;
    }

    public int getTotalItems() {
        return totalItems;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public UUID getContinuedFrom() {
        return continuedFrom;
    }
}
