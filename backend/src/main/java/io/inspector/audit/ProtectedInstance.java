package io.inspector.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * A composite ID marked protected by L3+ (R-SAFE-05): below the ADMIN floor every verb
 * against it is refused with the protection reason. Maps the Flyway-authored
 * {@code protected_instance} table (V1__init.sql).
 */
@Entity
@Table(name = "protected_instance")
@IdClass(ProtectedInstance.Key.class)
public class ProtectedInstance {

    @Id
    @Column(name = "engine_id", nullable = false)
    private String engineId;

    @Id
    @Column(name = "instance_id", nullable = false)
    private String instanceId;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "ts", nullable = false)
    private Instant ts;

    protected ProtectedInstance() {
        // JPA
    }

    public ProtectedInstance(String engineId, String instanceId, String reason, String createdBy, Instant ts) {
        this.engineId = engineId;
        this.instanceId = instanceId;
        this.reason = reason;
        this.createdBy = createdBy;
        this.ts = ts;
    }

    public String getEngineId() {
        return engineId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getReason() {
        return reason;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getTs() {
        return ts;
    }

    /** Composite primary key (engine_id, instance_id). */
    public static class Key implements Serializable {
        private String engineId;
        private String instanceId;

        public Key() {}

        public Key(String engineId, String instanceId) {
            this.engineId = engineId;
            this.instanceId = instanceId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k
                    && Objects.equals(engineId, k.engineId)
                    && Objects.equals(instanceId, k.instanceId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(engineId, instanceId);
        }
    }
}
