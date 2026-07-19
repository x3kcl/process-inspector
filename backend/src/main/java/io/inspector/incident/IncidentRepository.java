package io.inspector.incident;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The incident identity/state store (V18, INCIDENT-LEDGER.md §3.1). The INSERT path is a plain
 * JPA {@code save} (the {@code uq_incident} arbiter turns a concurrent double-insert into a
 * caught constraint violation); every LATER sampler write is one of the native
 * <b>state-conditional</b> UPDATEs below — each carries {@code WHERE state = <expected>} (plus
 * any gate predicate) and bumps {@code version} so it composes with JPA optimistic locking: an
 * interleaved human resolve/reopen (S3) makes the sampler's write return 0 rows (a MISS to
 * skip quietly), never a clobber.
 */
public interface IncidentRepository extends JpaRepository<Incident, Long> {

    Optional<Incident> findBySignatureHashAndAlgoVersion(String signatureHash, int algoVersion);

    /** The S2 list read, most-recently-seen first (bounded, unpaginated v1 — INCIDENT-LEDGER §6). */
    List<Incident> findAllByOrderByLastSeenDesc();

    /** The S2 list read with the {@code state=} filter (idx_incident_state). */
    List<Incident> findByStateOrderByLastSeenDesc(IncidentState state);

    /** The S2 list read with the {@code window=} recency filter pushed down (never in-memory). */
    List<Incident> findAllByLastSeenGreaterThanEqualOrderByLastSeenDesc(Instant since);

    /** The S2 list read with both {@code state=} and {@code window=} pushed down. */
    List<Incident> findByStateAndLastSeenGreaterThanEqualOrderByLastSeenDesc(IncidentState state, Instant since);

    /** The zero-state sweep's candidates: RESOLVED rows whose regression gate is still closed. */
    List<Incident> findByStateAndSeenZeroSinceResolveFalse(IncidentState state);

    /**
     * The per-cycle totals refresh for a row expected in {@code expectedState} (OPEN, REGRESSED,
     * or gate-closed RESOLVED — the data stays honest while only the state transition waits).
     * Never touches {@code seen_zero_since_resolve}. Returns 0 on a state race — skip quietly.
     */
    @Modifying
    @Transactional
    @Query(value = """
                    UPDATE incident
                    SET last_seen = :seenAt,
                        last_total = :total,
                        last_truncated = :truncated,
                        counts_by_engine = cast(:countsByEngine AS jsonb),
                        version = version + 1
                    WHERE id = :id AND state = :expectedState
                    """, nativeQuery = true)
    int updateObservedTotals(
            @Param("id") long id,
            @Param("expectedState") String expectedState,
            @Param("seenAt") Instant seenAt,
            @Param("total") long total,
            @Param("truncated") boolean truncated,
            @Param("countsByEngine") String countsByEngine);

    /**
     * The one deliberate absence-triggered write (INCIDENT-LEDGER §5): a post-resolve cycle
     * observed the class absent/zero, so the regression gate may open. Conditional on the row
     * still being RESOLVED with the flag unset.
     */
    @Modifying
    @Transactional
    @Query(value = """
                    UPDATE incident
                    SET seen_zero_since_resolve = true,
                        version = version + 1
                    WHERE id = :id AND state = 'RESOLVED' AND seen_zero_since_resolve = false
                    """, nativeQuery = true)
    int markSeenZeroSinceResolve(@Param("id") long id);

    /**
     * The RESOLVED → REGRESSED transition, gate re-checked IN the predicate (state AND
     * zero-state flag) so a racing resolve/reopen or a concurrent sampler makes this miss.
     * Resets the gate flag, stamps the regression counters, and refreshes the totals in the
     * same statement.
     */
    @Modifying
    @Transactional
    @Query(value = """
                    UPDATE incident
                    SET state = 'REGRESSED',
                        regression_count = regression_count + 1,
                        last_regressed_at = :seenAt,
                        seen_zero_since_resolve = false,
                        last_seen = :seenAt,
                        last_total = :total,
                        last_truncated = :truncated,
                        counts_by_engine = cast(:countsByEngine AS jsonb),
                        version = version + 1
                    WHERE id = :id AND state = 'RESOLVED' AND seen_zero_since_resolve = true
                    """, nativeQuery = true)
    int transitionToRegressed(
            @Param("id") long id,
            @Param("seenAt") Instant seenAt,
            @Param("total") long total,
            @Param("truncated") boolean truncated,
            @Param("countsByEngine") String countsByEngine);

    /**
     * The S3 human resolve (INCIDENT-LEDGER §6): OPEN/REGRESSED → RESOLVED, state-conditional on
     * the state the operator SAW ({@code expectedState}) so an interleaved sampler regression or
     * concurrent resolve makes this miss (0 rows ⇒ the verb answers 409, retryable — never a
     * clobber, never a 500). Resets {@code seen_zero_since_resolve} so the regression gate is
     * armed FRESH: a post-resolve zero observation is required all over again (§3.2/§5 zombie
     * guard). Resolve metadata (by/reason/ticket) lands on the closed EPISODE, not here.
     */
    @Modifying
    @Transactional
    @Query(value = """
                    UPDATE incident
                    SET state = 'RESOLVED',
                        seen_zero_since_resolve = false,
                        version = version + 1
                    WHERE id = :id AND state = :expectedState
                    """, nativeQuery = true)
    int transitionToResolved(@Param("id") long id, @Param("expectedState") String expectedState);

    /**
     * Fail-closed audit compensation for {@link #transitionToResolved}: the resolve's config
     * event could not be written, so the claim is undone — state back to what the operator saw.
     * {@code seen_zero_since_resolve} needs no restore: it is false in every non-RESOLVED state
     * (only {@link #markSeenZeroSinceResolve} sets it, RESOLVED-conditional). Conditional on the
     * row still being RESOLVED.
     */
    @Modifying
    @Transactional
    @Query(value = """
                    UPDATE incident
                    SET state = :previousState,
                        seen_zero_since_resolve = false,
                        version = version + 1
                    WHERE id = :id AND state = 'RESOLVED'
                    """, nativeQuery = true)
    int revertResolve(@Param("id") long id, @Param("previousState") String previousState);

    /**
     * The S3 human reopen ("resolved by mistake", INCIDENT-LEDGER §3.2): RESOLVED → OPEN,
     * distinct from the automatic REGRESSED transition — {@code regression_count} and
     * {@code last_regressed_at} are deliberately NOT touched (an undo is not a regression).
     * Clears the zero-state gate flag. State-conditional: 0 rows ⇒ 409, retryable.
     */
    @Modifying
    @Transactional
    @Query(value = """
                    UPDATE incident
                    SET state = 'OPEN',
                        seen_zero_since_resolve = false,
                        version = version + 1
                    WHERE id = :id AND state = 'RESOLVED'
                    """, nativeQuery = true)
    int transitionToReopened(@Param("id") long id);

    /**
     * Fail-closed audit compensation for {@link #transitionToReopened}: back to RESOLVED with the
     * zero-state gate flag restored to its pre-reopen value. Conditional on the row still being
     * OPEN.
     */
    @Modifying
    @Transactional
    @Query(value = """
                    UPDATE incident
                    SET state = 'RESOLVED',
                        seen_zero_since_resolve = :previousSeenZero,
                        version = version + 1
                    WHERE id = :id AND state = 'OPEN'
                    """, nativeQuery = true)
    int revertReopen(@Param("id") long id, @Param("previousSeenZero") boolean previousSeenZero);

    /**
     * The fail-closed audit compensation (mirrors {@code ErrorGroupAckService}): if the
     * regression's R-AUD-10 config event cannot be written, the transition is undone — state
     * back to RESOLVED with the gate re-armed and the counters restored ({@code previousRegressedAt}
     * is the pre-transition {@code last_regressed_at}, usually NULL). The refreshed totals are
     * deliberately KEPT (they are observation, not transition). Conditional on the row still
     * being REGRESSED.
     */
    @Modifying
    @Transactional
    @Query(value = """
                    UPDATE incident
                    SET state = 'RESOLVED',
                        regression_count = regression_count - 1,
                        last_regressed_at = cast(:previousRegressedAt AS timestamptz),
                        seen_zero_since_resolve = true,
                        version = version + 1
                    WHERE id = :id AND state = 'REGRESSED'
                    """, nativeQuery = true)
    int revertRegression(@Param("id") long id, @Param("previousRegressedAt") Instant previousRegressedAt);
}
