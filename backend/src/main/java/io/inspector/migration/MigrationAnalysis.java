package io.inspector.migration;

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

/** One stored cohort analysis (V27). The JSON columns are the history a re-GET returns. */
@Entity
@Table(name = "migration_analysis")
public class MigrationAnalysis {

    public enum Status {
        RUNNING,
        COMPLETED,
        FAILED
    }

    @Id
    private UUID id;

    @Column(name = "engine_id", nullable = false, updatable = false)
    private String engineId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cohort_filter", nullable = false, updatable = false)
    private String cohortFilter;

    @Column(name = "source_definition_id", nullable = false, updatable = false)
    private String sourceDefinitionId;

    @Column(name = "target_definition_id")
    private String targetDefinitionId;

    @Column(name = "taxonomy_version", nullable = false, updatable = false)
    private int taxonomyVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "code_counts", nullable = false)
    private String codeCounts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sample_instance_ids", nullable = false)
    private String sampleInstanceIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected MigrationAnalysis() {
        // JPA
    }

    public MigrationAnalysis(
            UUID id,
            String engineId,
            String cohortFilter,
            String sourceDefinitionId,
            String targetDefinitionId,
            int taxonomyVersion,
            String codeCounts,
            String sampleInstanceIds,
            String payload,
            Instant createdAt) {
        this.id = id;
        this.engineId = engineId;
        this.status = Status.RUNNING;
        this.cohortFilter = cohortFilter;
        this.sourceDefinitionId = sourceDefinitionId;
        this.targetDefinitionId = targetDefinitionId;
        this.taxonomyVersion = taxonomyVersion;
        this.codeCounts = codeCounts;
        this.sampleInstanceIds = sampleInstanceIds;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public void update(
            Status status,
            String targetDefinitionId,
            String codeCounts,
            String sampleInstanceIds,
            String payload,
            Instant finishedAt) {
        this.status = status;
        this.targetDefinitionId = targetDefinitionId;
        this.codeCounts = codeCounts;
        this.sampleInstanceIds = sampleInstanceIds;
        this.payload = payload;
        this.finishedAt = finishedAt;
    }

    public void fail(Instant finishedAt) {
        this.status = Status.FAILED;
        this.finishedAt = finishedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getEngineId() {
        return engineId;
    }

    public Status getStatus() {
        return status;
    }

    public String getCohortFilter() {
        return cohortFilter;
    }

    public String getSourceDefinitionId() {
        return sourceDefinitionId;
    }

    public String getTargetDefinitionId() {
        return targetDefinitionId;
    }

    public int getTaxonomyVersion() {
        return taxonomyVersion;
    }

    public String getCodeCounts() {
        return codeCounts;
    }

    public String getSampleInstanceIds() {
        return sampleInstanceIds;
    }

    public String getPayload() {
        return payload;
    }
}
