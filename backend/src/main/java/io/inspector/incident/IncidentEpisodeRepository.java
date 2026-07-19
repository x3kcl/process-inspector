package io.inspector.incident;

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
