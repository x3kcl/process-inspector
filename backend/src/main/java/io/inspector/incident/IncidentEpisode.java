package io.inspector.incident;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One open→resolve cycle of an incident (INCIDENT-LEDGER.md §3.2) — the MTTR substrate:
 * per-episode time-to-resolution is {@code endedAt − startedAt}, a plain column subtraction.
 * Opened on first sighting ({@code startState=OPEN}) and on each automatic regression
 * ({@code startState=REGRESSED}); closed by the S3 resolve verb, which stamps
 * {@code endedAt/resolvedBy/resolveReason/ticketId}. Exactly one live (NULL-{@code endedAt})
 * episode per non-RESOLVED incident — a service invariant, asserted by tests.
 *
 * <p>S1 exposes no setters: the sampler creates episodes and bumps {@code peak_total} via the
 * repository's native GREATEST update; the resolve/reopen mutations arrive with S3.
 */
@Entity
@Table(name = "incident_episode")
public class IncidentEpisode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private long incidentId;

    /** OPEN or REGRESSED only (DB CHECK) — an episode never starts resolved. */
    @Enumerated(EnumType.STRING)
    @Column(name = "start_state", nullable = false, updatable = false)
    private IncidentState startState;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    /** Max observed live total this episode (native GREATEST bump per cycle). */
    @Column(name = "peak_total", nullable = false)
    private long peakTotal;

    /** NULL while the episode is live. */
    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "resolve_reason")
    private String resolveReason;

    @Column(name = "ticket_id")
    private String ticketId;

    protected IncidentEpisode() {
        // JPA
    }

    /** The sampler's creation shape: a live episode starting at the sighting that opened it. */
    public IncidentEpisode(long incidentId, IncidentState startState, Instant startedAt, long peakTotal) {
        this.incidentId = incidentId;
        this.startState = startState;
        this.startedAt = startedAt;
        this.peakTotal = peakTotal;
    }

    public Long getId() {
        return id;
    }

    public long getIncidentId() {
        return incidentId;
    }

    public IncidentState getStartState() {
        return startState;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public long getPeakTotal() {
        return peakTotal;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public String getResolveReason() {
        return resolveReason;
    }

    public String getTicketId() {
        return ticketId;
    }
}
