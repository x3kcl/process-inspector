package io.inspector.triage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One persisted error-group acknowledgment SLICE (R-BAU-01): the group's signature is the
 * binding key, but persistence is keyed signature × engine × definition KEY (register
 * contract) so the resurface predicate can compare per-slice baselines — a new failing
 * definition version on ONE engine resurfaces the group even while the others are quiet.
 *
 * <p>Rows are current state, not history (the audit config-event rows are the history):
 * re-acknowledging replaces the signature's rows wholesale with fresh baselines, and
 * un-acknowledging deletes them. Immutable after insert — no setters by design.
 */
@Entity
@Table(name = "error_group_ack")
public class ErrorGroupAck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "signature_hash", nullable = false, updatable = false)
    private String signatureHash;

    @Column(name = "algo_version", nullable = false, updatable = false)
    private int algoVersion;

    @Column(name = "engine_id", nullable = false, updatable = false)
    private String engineId;

    @Column(name = "definition_key", nullable = false, updatable = false)
    private String definitionKey;

    @Column(name = "acknowledged_by", nullable = false, updatable = false)
    private String acknowledgedBy;

    @Column(name = "reason", nullable = false, updatable = false)
    private String reason;

    @Column(name = "ticket_id", updatable = false)
    private String ticketId;

    @Column(name = "acknowledged_at", nullable = false, updatable = false)
    private Instant acknowledgedAt;

    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    /** Failing-member count of THIS engine × definition slice when acknowledged (resurface baseline). */
    @Column(name = "acknowledged_count", nullable = false, updatable = false)
    private long acknowledgedCount;

    /** Highest FAILING definition version in the slice at ack time; null when unparsable. */
    @Column(name = "acknowledged_max_version", updatable = false)
    private Integer acknowledgedMaxVersion;

    protected ErrorGroupAck() {
        // JPA
    }

    public ErrorGroupAck(
            String signatureHash,
            int algoVersion,
            String engineId,
            String definitionKey,
            String acknowledgedBy,
            String reason,
            String ticketId,
            Instant acknowledgedAt,
            Instant expiresAt,
            long acknowledgedCount,
            Integer acknowledgedMaxVersion) {
        this.signatureHash = signatureHash;
        this.algoVersion = algoVersion;
        this.engineId = engineId;
        this.definitionKey = definitionKey;
        this.acknowledgedBy = acknowledgedBy;
        this.reason = reason;
        this.ticketId = ticketId;
        this.acknowledgedAt = acknowledgedAt;
        this.expiresAt = expiresAt;
        this.acknowledgedCount = acknowledgedCount;
        this.acknowledgedMaxVersion = acknowledgedMaxVersion;
    }

    public Long getId() {
        return id;
    }

    public String getSignatureHash() {
        return signatureHash;
    }

    public int getAlgoVersion() {
        return algoVersion;
    }

    public String getEngineId() {
        return engineId;
    }

    public String getDefinitionKey() {
        return definitionKey;
    }

    public String getAcknowledgedBy() {
        return acknowledgedBy;
    }

    public String getReason() {
        return reason;
    }

    public String getTicketId() {
        return ticketId;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public long getAcknowledgedCount() {
        return acknowledgedCount;
    }

    public Integer getAcknowledgedMaxVersion() {
        return acknowledgedMaxVersion;
    }

    /** A fresh detached copy (new row on re-insert) for audit-failure compensation. */
    public ErrorGroupAck detachedCopy() {
        return new ErrorGroupAck(
                signatureHash,
                algoVersion,
                engineId,
                definitionKey,
                acknowledgedBy,
                reason,
                ticketId,
                acknowledgedAt,
                expiresAt,
                acknowledgedCount,
                acknowledgedMaxVersion);
    }
}
