package io.inspector.incident;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** The episode store (V18, INCIDENT-LEDGER.md §3.2) — one row per open→resolve cycle. */
public interface IncidentEpisodeRepository extends JpaRepository<IncidentEpisode, Long> {

    /** The live episode (exactly one per non-RESOLVED incident — service invariant). */
    Optional<IncidentEpisode> findFirstByIncidentIdAndEndedAtIsNullOrderByStartedAtDesc(long incidentId);

    /** The per-episode history, newest first (idx_incident_episode_incident). */
    List<IncidentEpisode> findByIncidentIdOrderByStartedAtDesc(long incidentId);

    /** The LAST episode regardless of liveness — the reopen verb's target (INCIDENT-LEDGER §3.2). */
    Optional<IncidentEpisode> findFirstByIncidentIdOrderByStartedAtDesc(long incidentId);

    /**
     * The attention score's M factor and the §3.2 ack-expiry suggestion (ALARM-COST-MODEL.md §6,
     * #353): every CLOSED episode's duration in seconds, as {@code Object[]{incidentId,
     * seconds}}. Live episodes are excluded — an unfinished episode has no MTTR, and treating
     * "still broken" as "took this long" would be the exact dishonesty §6's honesty rails forbid.
     *
     * <p>Deliberately unbounded-by-window and unpaged: episode cardinality is
     * {@code 1 + regression_count} per incident and every regression needs a human resolve in
     * between (INCIDENT-LEDGER §3.2) — a pathological ledger has dozens, not thousands.
     */
    @Query(value = """
                    SELECT incident_id, EXTRACT(EPOCH FROM (ended_at - started_at))
                    FROM incident_episode
                    WHERE ended_at IS NOT NULL
                    """, nativeQuery = true)
    List<Object[]> closedEpisodeDurationSeconds();

    /**
     * The S3 resolve stamp (INCIDENT-LEDGER §3.2): closes one episode with the resolve metadata
     * — {@code ended_at}/{@code resolved_by}/{@code resolve_reason}/{@code ticket_id} live HERE,
     * not on {@code incident}. Conditional on the episode still being live, so it doubles as the
     * reopen-compensation restore (a just-reopened episode is live again). 0 rows = already
     * closed by a racer — skip quietly.
     */
    @Modifying
    @Transactional
    @Query(value = """
                    UPDATE incident_episode
                    SET ended_at = :endedAt,
                        resolved_by = :resolvedBy,
                        resolve_reason = :resolveReason,
                        ticket_id = :ticketId
                    WHERE id = :id AND ended_at IS NULL
                    """, nativeQuery = true)
    int closeEpisode(
            @Param("id") long id,
            @Param("endedAt") Instant endedAt,
            @Param("resolvedBy") String resolvedBy,
            @Param("resolveReason") String resolveReason,
            @Param("ticketId") String ticketId);

    /**
     * The S3 reopen (human undo, INCIDENT-LEDGER §3.2): the LAST episode goes live again —
     * {@code ended_at} back to NULL and the resolve metadata cleared — rather than minting a new
     * one ({@code regression_count} stays honest: a mistaken resolve was never a regression).
     * Also the resolve verb's audit-failure compensation (undoes {@link #closeEpisode}).
     */
    @Modifying
    @Transactional
    @Query(value = """
                    UPDATE incident_episode
                    SET ended_at = NULL,
                        resolved_by = NULL,
                        resolve_reason = NULL,
                        ticket_id = NULL
                    WHERE id = :id AND ended_at IS NOT NULL
                    """, nativeQuery = true)
    int reopenEpisode(@Param("id") long id);

    /**
     * Bumps the LIVE episode's peak to the cycle's observed total, monotonically ({@code
     * GREATEST} — a dipping total never lowers a peak). No-ops harmlessly (0 rows) when no
     * episode is live, e.g. after an interleaved resolve — the skip-quietly doctrine.
     */
    @Modifying
    @Transactional
    @Query(value = """
                    UPDATE incident_episode
                    SET peak_total = GREATEST(peak_total, :total)
                    WHERE incident_id = :incidentId AND ended_at IS NULL
                    """, nativeQuery = true)
    int bumpLivePeak(@Param("incidentId") long incidentId, @Param("total") long total);
}
