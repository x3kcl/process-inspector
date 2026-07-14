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
 * A process-definition key marked protected by L3+ (R-SAFE-05, #172): the definition-scope half
 * deferred from #165. Below the ADMIN floor every verb against any instance of this key (or the
 * definition itself) is refused with the protection reason. Maps the Flyway-authored
 * {@code protected_definition} table (V17__protected_definition.sql).
 */
@Entity
@Table(name = "protected_definition")
@IdClass(ProtectedDefinition.Key.class)
public class ProtectedDefinition {

    @Id
    @Column(name = "engine_id", nullable = false)
    private String engineId;

    @Id
    @Column(name = "definition_key", nullable = false)
    private String definitionKey;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "ts", nullable = false)
    private Instant ts;

    protected ProtectedDefinition() {
        // JPA
    }

    public ProtectedDefinition(String engineId, String definitionKey, String reason, String createdBy, Instant ts) {
        this.engineId = engineId;
        this.definitionKey = definitionKey;
        this.reason = reason;
        this.createdBy = createdBy;
        this.ts = ts;
    }

    public String getEngineId() {
        return engineId;
    }

    public String getDefinitionKey() {
        return definitionKey;
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

    /** Composite primary key (engine_id, definition_key). */
    public static class Key implements Serializable {
        private String engineId;
        private String definitionKey;

        public Key() {}

        public Key(String engineId, String definitionKey) {
            this.engineId = engineId;
            this.definitionKey = definitionKey;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k
                    && Objects.equals(engineId, k.engineId)
                    && Objects.equals(definitionKey, k.definitionKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(engineId, definitionKey);
        }
    }
}
