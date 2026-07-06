package io.inspector.bulk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One member of a bulk job (SPEC §7): {@code pending → dispatched → (ok | failed |
 * skipped | skipped_protected | unknown | not_run)}. Outcomes are the per-item report the
 * drawer renders verbatim; {@code unknown} is never auto-retried and carries the
 * Verify-now affordance (R-SAFE-09).
 */
@Entity
@Table(name = "bulk_job_item")
@IdClass(BulkJobItem.Key.class)
public class BulkJobItem {

    /** Lowercase names match the Flyway CHECK constraint verbatim. */
    public enum State {
        pending,
        dispatched,
        ok,
        failed,
        skipped,
        skipped_protected,
        unknown,
        not_run
    }

    @Id
    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Id
    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "engine_id", nullable = false)
    private String engineId;

    @Column(name = "instance_id", nullable = false)
    private String instanceId;

    @Column(name = "job_ref")
    private String jobRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private State state;

    @Column(name = "detail")
    private String detail;

    @Column(name = "audit_id")
    private UUID auditId;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected BulkJobItem() {
        // JPA
    }

    public BulkJobItem(UUID jobId, int ordinal, String engineId, String instanceId, String jobRef, State state) {
        this.jobId = jobId;
        this.ordinal = ordinal;
        this.engineId = engineId;
        this.instanceId = instanceId;
        this.jobRef = jobRef;
        this.state = state;
    }

    public void markDispatched() {
        this.state = State.dispatched;
    }

    public void settle(State outcome, String detail, UUID auditId, Instant at) {
        this.state = outcome;
        this.detail = detail;
        this.auditId = auditId;
        this.finishedAt = at;
    }

    public UUID getJobId() {
        return jobId;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public String getEngineId() {
        return engineId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getJobRef() {
        return jobRef;
    }

    public State getState() {
        return state;
    }

    public String getDetail() {
        return detail;
    }

    public UUID getAuditId() {
        return auditId;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public static class Key implements Serializable {
        private UUID jobId;
        private int ordinal;

        public Key() {}

        public Key(UUID jobId, int ordinal) {
            this.jobId = jobId;
            this.ordinal = ordinal;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return ordinal == key.ordinal && Objects.equals(jobId, key.jobId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(jobId, ordinal);
        }
    }
}
