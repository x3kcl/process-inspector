package io.inspector.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Operator note per composite ID (SPEC §9) — BFF-owned, author + timestamp. Maps the
 * Flyway-authored {@code instance_note} table (V1__init.sql).
 */
@Entity
@Table(name = "instance_note")
public class InstanceNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "engine_id", nullable = false)
    private String engineId;

    @Column(name = "instance_id", nullable = false)
    private String instanceId;

    @Column(name = "author", nullable = false)
    private String author;

    @Column(name = "ts", nullable = false)
    private Instant ts;

    @Column(name = "body", nullable = false)
    private String body;

    protected InstanceNote() {
        // JPA
    }

    public InstanceNote(String engineId, String instanceId, String author, Instant ts, String body) {
        this.engineId = engineId;
        this.instanceId = instanceId;
        this.author = author;
        this.ts = ts;
        this.body = body;
    }

    public Long getId() {
        return id;
    }

    public String getEngineId() {
        return engineId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getAuthor() {
        return author;
    }

    public Instant getTs() {
        return ts;
    }

    public String getBody() {
        return body;
    }
}
