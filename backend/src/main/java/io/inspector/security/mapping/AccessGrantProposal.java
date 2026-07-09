package io.inspector.security.mapping;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A pending four-eyes proposal (IDP-SECURITY.md §6, V14). A widening mapping write is parked here
 * until a second independent {@code ACCESS_ADMIN} approves it. Maps onto {@code access_grant_proposal}.
 */
@Entity
@Table(name = "access_grant_proposal")
public class AccessGrantProposal {

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

    @Column(name = "group_name", nullable = false, updatable = false)
    private String groupName;

    @Column(name = "change_kind", nullable = false, updatable = false)
    private String changeKind;

    @Column(name = "change_json", nullable = false, updatable = false)
    private String changeJson;

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

    protected AccessGrantProposal() {
        // JPA
    }

    public AccessGrantProposal(
            String proposer,
            String groupName,
            GrantChange.Kind changeKind,
            String changeJson,
            String summary,
            String reason,
            Instant createdAt,
            Instant expiresAt) {
        this.proposer = proposer;
        this.groupName = groupName;
        this.changeKind = changeKind.name();
        this.changeJson = changeJson;
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

    public String getGroupName() {
        return groupName;
    }

    public String getChangeJson() {
        return changeJson;
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
