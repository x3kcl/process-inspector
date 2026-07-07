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

    /** SPEC §7 v1: grid-selection (and error-class) bulk is capped at 200 items. */
    public static final int ITEM_CAP = 200;

    /**
     * SPEC §7 v1.x #2: the filter-resolved path ("select all matching") may carry up to
     * 5000 items — the query-bulk hard cap, matched by the V3 CHECK constraint.
     */
    public static final int FILTER_ITEM_CAP = 5000;

    public enum State {
        PENDING,
        RUNNING,
        COMPLETED,
        CANCELLED,
        INTERRUPTED
    }

    /**
     * Scope provenance (usability fix E1, V4__bulk_job_scope.sql): which door the job came
     * through — the ticked-row grid selection, the triage-landing error-class group retry,
     * or the select-all-matching-filter bulk. Paired with {@link #scopeLabel} for a
     * human-readable one-liner in the operations drawer.
     */
    public enum ScopeKind {
        SELECTION,
        ERROR_CLASS,
        FILTER
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

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_kind", nullable = false)
    private ScopeKind scopeKind;

    @Column(name = "scope_label")
    private String scopeLabel;

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
        this(
                id,
                submittedBy,
                submittedAt,
                verb,
                reason,
                ticketId,
                totalItems,
                continuedFrom,
                ScopeKind.SELECTION,
                null);
    }

    /** Full constructor threading the scope descriptor from one of the three submit doors. */
    public BulkJob(
            UUID id,
            String submittedBy,
            Instant submittedAt,
            String verb,
            String reason,
            String ticketId,
            int totalItems,
            UUID continuedFrom,
            ScopeKind scopeKind,
            String scopeLabel) {
        this.id = id;
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
        this.verb = verb;
        this.reason = reason;
        this.ticketId = ticketId;
        this.state = State.PENDING;
        this.totalItems = totalItems;
        this.continuedFrom = continuedFrom;
        this.scopeKind = scopeKind;
        this.scopeLabel = scopeLabel;
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

    public ScopeKind getScopeKind() {
        return scopeKind;
    }

    public String getScopeLabel() {
        return scopeLabel;
    }
}
