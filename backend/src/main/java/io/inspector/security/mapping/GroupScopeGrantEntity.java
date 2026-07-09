package io.inspector.security.mapping;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One ladder grant row (group → role on an engine/tenant scope). Maps onto {@code group_scope_grant}
 * (V13) — schema is the truth, {@code ddl-auto=validate}. Grants are value tuples
 * ({@code UNIQUE(group,role,engine,tenant)}), so an edit is delete+insert; there is no mutable
 * natural key (IDP-SECURITY.md §6).
 */
@Entity
@Table(name = "group_scope_grant")
public class GroupScopeGrantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "group_name", nullable = false, updatable = false)
    private String groupName;

    @Column(name = "role", nullable = false, updatable = false)
    private String role;

    @Column(name = "engine_id", nullable = false, updatable = false)
    private String engineId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "source", nullable = false, updatable = false)
    private String source;

    protected GroupScopeGrantEntity() {
        // JPA
    }

    public GroupScopeGrantEntity(
            String groupName, String role, String engineId, String tenantId, String source, Instant at) {
        this.groupName = groupName;
        this.role = role;
        this.engineId = engineId;
        this.tenantId = tenantId;
        this.source = source;
        this.createdAt = at;
        this.updatedAt = at;
    }

    public Long getId() {
        return id;
    }

    public String getGroupName() {
        return groupName;
    }

    public String getRole() {
        return role;
    }

    public String getEngineId() {
        return engineId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getSource() {
        return source;
    }
}
