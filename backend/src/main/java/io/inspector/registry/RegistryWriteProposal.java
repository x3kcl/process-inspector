package io.inspector.registry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A pending four-eyes proposal for a dangerous registry write (docs/REGISTRY-CRUD.md §9, V16, #91).
 * Parked here until a second independent {@code REGISTRY_ADMIN} approves. Maps onto
 * {@code registry_write_proposal}; mirrors {@code io.inspector.security.mapping.AccessGrantProposal}.
 */
@Entity
@Table(name = "registry_write_proposal")
public class RegistryWriteProposal {

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED,
        EXPIRED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "proposer", nullable = false, updatable = false)
    private String proposer;

    @Column(name = "proposer_groups", nullable = false, updatable = false)
    private String proposerGroups;

    @Column(name = "engine_id", nullable = false, updatable = false)
    private String engineId;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    @Column(name = "summary", nullable = false, updatable = false)
    private String summary;

    @Column(name = "reason", nullable = false, updatable = false)
    private String reason;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "approver")
    private String approver;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected RegistryWriteProposal() {
        // JPA
    }

    public RegistryWriteProposal(
            String proposer,
            String proposerGroups,
            String engineId,
            RegistryChange.Kind kind,
            String summary,
            String reason,
            Instant createdAt,
            Instant expiresAt) {
        this.proposer = proposer;
        this.proposerGroups = proposerGroups;
        this.engineId = engineId;
        this.kind = kind.name();
        this.summary = summary;
        this.reason = reason;
        this.status = Status.PENDING.name();
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public void decide(Status decision, String approver, Instant at) {
        this.status = decision.name();
        this.approver = approver;
        this.decidedAt = at;
    }

    public Long getId() {
        return id;
    }

    public String getProposer() {
        return proposer;
    }

    public String getProposerGroups() {
        return proposerGroups;
    }

    public String getEngineId() {
        return engineId;
    }

    public String getKind() {
        return kind;
    }

    public String getSummary() {
        return summary;
    }

    public String getReason() {
        return reason;
    }

    public Status getStatus() {
        return Status.valueOf(status);
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getApprover() {
        return approver;
    }
}
