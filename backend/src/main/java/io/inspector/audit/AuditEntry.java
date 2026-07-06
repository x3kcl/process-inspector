package io.inspector.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of the append-only audit golden master (SPEC §9, R-AUD-02). Maps EXACTLY onto
 * the Flyway-authored {@code audit_entry} table (V1__init.sql) — the schema is the truth,
 * this class follows it (M4 build-order constraint). The spec field {@code user} is column
 * {@code actor} ("user" is reserved in Postgres).
 *
 * Lifecycle (R-SEM-18): inserted {@code PENDING} BEFORE the engine call (fail-closed,
 * R-AUD-01), closed to {@code ok|failed|unknown} afterwards. Insert-time columns are
 * frozen by a DB trigger; only the outcome columns below marked mutable ever change.
 */
@Entity
@Table(name = "audit_entry")
public class AuditEntry {

    /** Response/payload snippets are capped at 32 KiB, truncated + flagged (R-AUD-02). */
    public static final int SNIPPET_MAX_BYTES = 32 * 1024;

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /** DB-assigned chain position (sequence default) — read-only from Java. */
    @Column(name = "seq", insertable = false, updatable = false)
    private Long seq;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private String correlationId;

    /** M5 bulk envelope linkage; null for single-target verbs. */
    @Column(name = "bulk_job_id", updatable = false)
    private UUID bulkJobId;

    @Column(name = "actor", nullable = false, updatable = false)
    private String actor;

    @Column(name = "ts", nullable = false, updatable = false)
    private Instant ts;

    @Column(name = "engine_id", nullable = false, updatable = false)
    private String engineId;

    @Column(name = "tenant_id", updatable = false)
    private String tenantId;

    /** Null for definition-scoped verbs (suspend-definition). */
    @Column(name = "instance_id", updatable = false)
    private String instanceId;

    @Column(name = "action", nullable = false, updatable = false)
    private String action;

    @Column(name = "reason", updatable = false)
    private String reason;

    @Column(name = "ticket_id", updatable = false)
    private String ticketId;

    /** Per-verb versioned JSON payload (already serialized + secret-redacted). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", updatable = false)
    private String payload;

    /* ---- mutable outcome columns (PENDING → ok | failed | unknown) ---- */

    @Column(name = "http_status")
    private Integer httpStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false)
    private AuditOutcome outcome;

    @Column(name = "response_snippet")
    private String responseSnippet;

    @Column(name = "response_truncated", nullable = false)
    private boolean responseTruncated;

    /* ---- immutable flags ---- */

    @Column(name = "break_glass", nullable = false, updatable = false)
    private boolean breakGlass;

    @Column(name = "approved_by", updatable = false)
    private String approvedBy;

    /** Tamper-evidence hash over the insert-time columns + previous row's hash. */
    @Column(name = "chain_hash", updatable = false)
    private String chainHash;

    protected AuditEntry() {
        // JPA
    }

    public AuditEntry(
            UUID id,
            String correlationId,
            String actor,
            Instant ts,
            String engineId,
            String tenantId,
            String instanceId,
            String action,
            String reason,
            String ticketId,
            String payload,
            boolean breakGlass) {
        this.id = id;
        this.correlationId = correlationId;
        this.actor = actor;
        this.ts = ts;
        this.engineId = engineId;
        this.tenantId = tenantId;
        this.instanceId = instanceId;
        this.action = action;
        this.reason = reason;
        this.ticketId = ticketId;
        this.payload = payload;
        this.breakGlass = breakGlass;
        this.outcome = AuditOutcome.PENDING;
    }

    /** Close the PENDING row (or reconcile an unknown one) — the only legal update. */
    public void close(AuditOutcome newOutcome, Integer httpStatus, String snippet, boolean truncated) {
        this.outcome = newOutcome;
        this.httpStatus = httpStatus;
        this.responseSnippet = snippet;
        this.responseTruncated = truncated;
    }

    void setChainHash(String chainHash) {
        this.chainHash = chainHash;
    }

    public UUID getId() {
        return id;
    }

    public Long getSeq() {
        return seq;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public UUID getBulkJobId() {
        return bulkJobId;
    }

    public String getActor() {
        return actor;
    }

    public Instant getTs() {
        return ts;
    }

    public String getEngineId() {
        return engineId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    public String getTicketId() {
        return ticketId;
    }

    public String getPayload() {
        return payload;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public AuditOutcome getOutcome() {
        return outcome;
    }

    public String getResponseSnippet() {
        return responseSnippet;
    }

    public boolean isResponseTruncated() {
        return responseTruncated;
    }

    public boolean isBreakGlass() {
        return breakGlass;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public String getChainHash() {
        return chainHash;
    }
}
