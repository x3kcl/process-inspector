package io.inspector.security.mapping;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One fleet grant row (group → REGISTRY_ADMIN | ACCESS_ADMIN). Maps onto {@code group_fleet_grant}
 * (V13), orthogonal to the ladder — a fleet grant must never read as a {@code (role,engine,tenant)}
 * row. {@code UNIQUE(group, grant_kind)} (IDP-SECURITY.md §11).
 */
@Entity
@Table(name = "group_fleet_grant")
public class GroupFleetGrantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "group_name", nullable = false, updatable = false)
    private String groupName;

    @Column(name = "grant_kind", nullable = false, updatable = false)
    private String grantKind;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "source", nullable = false, updatable = false)
    private String source;

    protected GroupFleetGrantEntity() {
        // JPA
    }

    public GroupFleetGrantEntity(String groupName, FleetGrant grantKind, String source, Instant at) {
        this.groupName = groupName;
        this.grantKind = grantKind.name();
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

    public FleetGrant getGrantKind() {
        return FleetGrant.valueOf(grantKind);
    }

    public String getSource() {
        return source;
    }
}
