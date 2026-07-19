package io.inspector.incident;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One persisted failure class (R-BAU-10, V18__incident_ledger.sql): the fleet-wide
 * {@code (signature_hash, algo_version)} identity — the R-SEM-03 binding contract, exactly as
 * acks — carrying live lifecycle state. Mutable BY DESIGN (unlike audit rows): lifecycle
 * history lives in {@link IncidentEpisode} rows and R-AUD-10 config-event audit entries.
 *
 * <p>Concurrency doctrine (INCIDENT-LEDGER §3.1): every transition is optimistic-locked (the
 * {@code @Version} column) AND the sampler's writes are state-conditional native UPDATEs in
 * {@link IncidentRepository} (each bumping {@code version}) — an interleaved human
 * resolve/reopen makes a sampler write MISS rather than clobber. This entity therefore exposes
 * no setters: the INSERT path constructs it; every later write goes through the conditional
 * updates.
 *
 * <p>{@code countsByEngine} maps the jsonb display blob as its serialized String (the
 * {@code @JdbcTypeCode(SqlTypes.JSON)} pattern of {@code AuditEntry.payload}) — Jackson
 * (de)serialization happens at the service edge, mirroring the audit precedent.
 */
@Entity
@Table(name = "incident")
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "signature_hash", nullable = false, updatable = false)
    private String signatureHash;

    @Column(name = "algo_version", nullable = false, updatable = false)
    private int algoVersion;

    /** Null when no stacktrace could refine the group (mirrors {@code ErrorGroup}). */
    @Column(name = "exception_class", updatable = false)
    private String exceptionClass;

    @Column(name = "normalized_message", nullable = false, updatable = false)
    private String normalizedMessage;

    /** One REAL member message — the same R-SEM-03-sanitized string Stage 0 renders. */
    @Column(name = "sample_raw_message", nullable = false, updatable = false)
    private String sampleRawMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private IncidentState state;

    @Column(name = "first_seen", nullable = false, updatable = false)
    private Instant firstSeen;

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;

    /** Lower bound when {@code lastTruncated} (R-SEM-12). */
    @Column(name = "last_total", nullable = false)
    private long lastTotal;

    @Column(name = "last_truncated", nullable = false)
    private boolean lastTruncated;

    /** Latest {@code engineId → "defKey:vN" → count} display blob, serialized JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "counts_by_engine", nullable = false)
    private String countsByEngine;

    /** The regression zero-state gate (INCIDENT-LEDGER §5): one post-resolve cycle saw absent/zero. */
    @Column(name = "seen_zero_since_resolve", nullable = false)
    private boolean seenZeroSinceResolve;

    @Column(name = "regression_count", nullable = false)
    private int regressionCount;

    @Column(name = "last_regressed_at")
    private Instant lastRegressedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Incident() {
        // JPA
    }

    /** The first-sighting INSERT shape: state OPEN, first==last seen, gate flags at defaults. */
    public Incident(
            String signatureHash,
            int algoVersion,
            String exceptionClass,
            String normalizedMessage,
            String sampleRawMessage,
            Instant seenAt,
            long total,
            boolean truncated,
            String countsByEngine) {
        this.signatureHash = signatureHash;
        this.algoVersion = algoVersion;
        this.exceptionClass = exceptionClass;
        this.normalizedMessage = normalizedMessage;
        this.sampleRawMessage = sampleRawMessage;
        this.state = IncidentState.OPEN;
        this.firstSeen = seenAt;
        this.lastSeen = seenAt;
        this.lastTotal = total;
        this.lastTruncated = truncated;
        this.countsByEngine = countsByEngine;
        this.seenZeroSinceResolve = false;
        this.regressionCount = 0;
    }

    public Long getId() {
        return id;
    }

    public String getSignatureHash() {
        return signatureHash;
    }

    public int getAlgoVersion() {
        return algoVersion;
    }

    public String getExceptionClass() {
        return exceptionClass;
    }

    public String getNormalizedMessage() {
        return normalizedMessage;
    }

    public String getSampleRawMessage() {
        return sampleRawMessage;
    }

    public IncidentState getState() {
        return state;
    }

    public Instant getFirstSeen() {
        return firstSeen;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    public long getLastTotal() {
        return lastTotal;
    }

    public boolean isLastTruncated() {
        return lastTruncated;
    }

    public String getCountsByEngine() {
        return countsByEngine;
    }

    public boolean isSeenZeroSinceResolve() {
        return seenZeroSinceResolve;
    }

    public int getRegressionCount() {
        return regressionCount;
    }

    public Instant getLastRegressedAt() {
        return lastRegressedAt;
    }

    public long getVersion() {
        return version;
    }
}
