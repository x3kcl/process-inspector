package io.inspector.migration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One stored hierarchy migration plan (V28, #497): the call-activity tree under its root(s) as
 * enumerated at create. Immutable — the operator's per-definition choices are
 * {@link HierarchyPlanGroup} rows.
 */
@Entity
@Table(name = "migration_hierarchy_plan")
public class HierarchyPlan {

    @Id
    private UUID id;

    @Column(name = "engine_id", nullable = false, updatable = false)
    private String engineId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    protected HierarchyPlan() {
        // JPA
    }

    public HierarchyPlan(UUID id, String engineId, String createdBy, Instant createdAt, String payload) {
        this.id = id;
        this.engineId = engineId;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.payload = payload;
    }

    public UUID getId() {
        return id;
    }

    public String getEngineId() {
        return engineId;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getPayload() {
        return payload;
    }
}
