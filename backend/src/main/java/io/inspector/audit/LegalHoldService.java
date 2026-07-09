package io.inspector.audit;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Legal holds (M4-CLOSEOUT §5c / S5b): engine/tenant/time-window records that suspend the retention
 * purge for overlapping audit partitions. The hold is enforced <b>in the DB</b> by {@code
 * purge_audit()}; this service is the human set/release surface, each audited as a fail-closed
 * config event with the acting human as {@code actor}.
 *
 * <p><b>Fail-closed ordering (R-AUD-10 trichotomy = fail-closed):</b> the state change and its
 * config event must both land or neither does. Since {@code recordConfigEvent} must commit on its
 * own (it serializes the tamper-evidence chain under a lock and cannot run inside an outer
 * transaction), we write the state change, then the event, and <b>compensate</b> the state change
 * if the event throws — the same shape {@code ScopeMappingService} uses for its reload.
 */
@Service
public class LegalHoldService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public LegalHoldService(JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    /** Place a hold; returns its id. The config event carries the acting human. */
    public UUID set(String engineId, String tenantId, Instant fromTs, Instant toTs, String reason, String actor) {
        String cleanReason = reason == null ? "" : reason.strip();
        if (cleanReason.length() < 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a legal-hold reason must be at least 10 chars");
        }
        if (fromTs == null || toTs == null || !toTs.isAfter(fromTs)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "legal-hold window must have toTs after fromTs");
        }

        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO legal_hold (id, engine_id, tenant_id, from_ts, to_ts, reason, created_by, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                blankToNull(engineId),
                blankToNull(tenantId),
                Timestamp.from(fromTs),
                Timestamp.from(toTs),
                cleanReason,
                actor,
                Timestamp.from(clock.instant()));
        try {
            audit.recordConfigEvent(
                    "audit-legal-hold-set", actor, true, setPayload(id, engineId, tenantId, fromTs, toTs, cleanReason));
        } catch (RuntimeException e) {
            // Fail-closed: the hold must not take effect unaudited — compensate the insert.
            jdbc.update("DELETE FROM legal_hold WHERE id = ?", id);
            throw e;
        }
        return id;
    }

    /** Release an active hold. 404 if unknown or already released. */
    public void release(UUID id, String actor) {
        int released = jdbc.update(
                "UPDATE legal_hold SET released_at = ?, released_by = ? WHERE id = ? AND released_at IS NULL",
                Timestamp.from(clock.instant()),
                actor,
                id);
        if (released == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no active legal hold with id " + id);
        }
        try {
            audit.recordConfigEvent("audit-legal-hold-release", actor, true, Map.of("id", id.toString()));
        } catch (RuntimeException e) {
            // Fail-closed: keep the hold active (protective) if the release cannot be audited.
            jdbc.update("UPDATE legal_hold SET released_at = NULL, released_by = NULL WHERE id = ?", id);
            throw e;
        }
    }

    /** Active (unreleased) holds, earliest window first. */
    public List<LegalHoldDto> listActive() {
        return jdbc.query(
                "SELECT id, engine_id, tenant_id, from_ts, to_ts, reason, created_by, created_at"
                        + " FROM legal_hold WHERE released_at IS NULL ORDER BY from_ts",
                (rs, i) -> new LegalHoldDto(
                        rs.getObject("id", UUID.class),
                        rs.getString("engine_id"),
                        rs.getString("tenant_id"),
                        rs.getTimestamp("from_ts").toInstant(),
                        rs.getTimestamp("to_ts").toInstant(),
                        rs.getString("reason"),
                        rs.getString("created_by"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    private static Map<String, Object> setPayload(
            UUID id, String engineId, String tenantId, Instant fromTs, Instant toTs, String reason) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", id.toString());
        p.put("engineId", blankToNull(engineId));
        p.put("tenantId", blankToNull(tenantId));
        p.put("fromTs", fromTs.toString());
        p.put("toTs", toTs.toString());
        p.put("reason", reason);
        return p;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** A single legal hold as returned by the admin surface. */
    public record LegalHoldDto(
            UUID id,
            String engineId,
            String tenantId,
            Instant fromTs,
            Instant toTs,
            String reason,
            String createdBy,
            Instant createdAt) {}
}
