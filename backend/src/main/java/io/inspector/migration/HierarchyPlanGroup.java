package io.inspector.migration;

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
 * The operator's choice for one source definition of a hierarchy plan (V28, #497). No row means
 * the group is still pending. {@code CONFIGURED} names the cohort analysis that pins the target,
 * the mapping document and each instance's compare-and-set digest.
 */
@Entity
@Table(name = "migration_hierarchy_group")
@IdClass(HierarchyPlanGroup.Key.class)
public class HierarchyPlanGroup {

    public enum Decision {
        CONFIGURED,
        SKIPPED
    }

    @Id
    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Id
    @Column(name = "definition_id", nullable = false)
    private String definitionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false)
    private Decision decision;

    @Column(name = "analysis_id")
    private UUID analysisId;

    @Column(name = "decided_by", nullable = false)
    private String decidedBy;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    protected HierarchyPlanGroup() {
        // JPA
    }

    public HierarchyPlanGroup(UUID planId, String definitionId) {
        this.planId = planId;
        this.definitionId = definitionId;
    }

    public void decide(Decision decision, UUID analysisId, String by, Instant at) {
        this.decision = decision;
        this.analysisId = analysisId;
        this.decidedBy = by;
        this.decidedAt = at;
    }

    public UUID getPlanId() {
        return planId;
    }

    public String getDefinitionId() {
        return definitionId;
    }

    public Decision getDecision() {
        return decision;
    }

    public UUID getAnalysisId() {
        return analysisId;
    }

    public static class Key implements Serializable {
        private UUID planId;
        private String definitionId;

        public Key() {}

        public Key(UUID planId, String definitionId) {
            this.planId = planId;
            this.definitionId = definitionId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return Objects.equals(planId, key.planId) && Objects.equals(definitionId, key.definitionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(planId, definitionId);
        }
    }
}
