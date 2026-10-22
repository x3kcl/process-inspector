package io.inspector.snapshot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One definition key's starts in one sample bucket. Schema is V24. */
@Entity
@Table(name = "triage_start_by_key")
public class StartByKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "engine_id", nullable = false)
    private String engineId;

    @Column(name = "definition_key", nullable = false)
    private String definitionKey;

    @Column(name = "count", nullable = false)
    private long count;

    @Column(name = "catalog_capped", nullable = false)
    private boolean catalogCapped;

    @Column(name = "sampled_at", nullable = false)
    private Instant sampledAt;

    protected StartByKey() {}

    public StartByKey(String engineId, String definitionKey, long count, boolean catalogCapped, Instant sampledAt) {
        this.engineId = engineId;
        this.definitionKey = definitionKey;
        this.count = count;
        this.catalogCapped = catalogCapped;
        this.sampledAt = sampledAt;
    }

    public String getDefinitionKey() {
        return definitionKey;
    }

    public long getCount() {
        return count;
    }

    public boolean isCatalogCapped() {
        return catalogCapped;
    }
}
