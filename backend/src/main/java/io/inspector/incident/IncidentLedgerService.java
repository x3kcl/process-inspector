package io.inspector.incident;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.AuditService;
import io.inspector.audit.AuditUnavailableException;
import io.inspector.config.InspectorProperties;
import io.inspector.dto.ErrorGroup;
import io.inspector.snapshot.AggregationSample;
import io.inspector.snapshot.AggregationSampledEvent;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Incident Ledger's ingestion state machine (R-BAU-10, INCIDENT-LEDGER.md §5): a pure
 * DB-side consumer of the sampler's {@link AggregationSampledEvent} — ZERO engine calls of its
 * own; disabling the sampler idles this store too. Gated independently by
 * {@code inspector.incidents.enabled} so an operator can switch the ledger off without losing
 * the trend sparklines.
 *
 * <p>Per cycle, per live group (identity = the R-SEM-03 {@code (signatureHash, algoVersion)}
 * binding contract, fleet-wide): first sighting INSERTs an OPEN incident + its live episode;
 * OPEN/REGRESSED refresh totals and bump the live episode's peak; RESOLVED sits behind the
 * <b>regression gate</b> — {@code seen_zero_since_resolve} (at least one post-resolve cycle
 * observed the class absent/zero, killing the cache/retry-lag "zombie incident") AND
 * {@code total >= regression-min-count} — and while gated the cycle still refreshes
 * totals/occurrence (data stays honest; only the state transition waits). Absent/zero groups
 * write nothing except the one deliberate absence-triggered write: arming the zero-state flag
 * on RESOLVED rows. Every cycle upserts the bucketed occurrence row (idempotent, mirrors the
 * snapshot store).
 *
 * <p><b>"Observed" requires the owning engine was reached (#302, R-BAU-10)</b>: the
 * absence-triggered write above only ever fires on a {@link AggregationSample#cycleComplete()}
 * cycle — i.e. every registry engine's envelope came back {@code ok()} this pass. A cycle where
 * one engine was unreachable cannot tell "the class is gone" apart from "we didn't get to look",
 * so it skips the zero-state sweep entirely rather than arm the gate from a gap. Live groups
 * still ingest normally on an incomplete cycle — but every occurrence row now PERSISTS the
 * marker ({@code incident_occurrence.cycle_complete}, V21) rather than dropping it on the floor:
 * a blind row's counts are missing the unreachable engine's members, so the series must let
 * every downstream reader (attention arrivals, RETRYING spell edges) discard it exactly the way
 * they already discard a truncated one. Writing the row without the marker is what turned a
 * two-minute outage into hundreds of phantom arrivals and into fabricated SELF_HEALED evidence.
 *
 * <p><b>Transaction boundaries — one transaction per GROUP, not per cycle</b> (design left the
 * call to the implementation): a cycle touches many unrelated failure classes, and a
 * per-cycle transaction would let one poisoned group roll back every other group's honest
 * observation — the blast-radius inversion of the do-no-harm rule. Each group's writes
 * (insert+episode+occurrence, or update+peak+occurrence, or the regression triple) are small
 * and atomic per group; a failed group is skipped with ONE warn per cycle and the next cycle
 * reconciles.
 *
 * <p><b>Race doctrine</b> (INCIDENT-LEDGER §3.1): the INSERT path relies on the
 * {@code uq_incident} arbiter (a concurrent double-insert is caught and skipped); every later
 * write is a state-conditional native UPDATE that bumps {@code version} — an interleaved human
 * resolve/reopen makes a sampler write MISS (0 rows), and a miss is a quiet skip, never a
 * clobber. The REGRESSED transition writes its R-AUD-10 config-event audit row fail-closed
 * (the {@code ErrorGroupAckService} discipline): if the audit write fails, the transition is
 * compensated away and only the observed totals survive — but a poll cycle never crashes, so
 * the failure is a warn, not a thrown 503.
 *
 * <p><b>Why the compensation is reachable, not dead code (issue #307, true-up after #316):</b>
 * {@link #regress}'s {@code audit.recordConfigEvent} call PARTICIPATES in this group's ambient
 * {@link #tx} (the plain {@code REQUIRED}-propagation template — see {@link #ingestGroup}), but
 * {@link AuditService}'s own insert runs in ITS OWN {@code PROPAGATION_REQUIRES_NEW} transaction
 * (see {@code AuditService#chainLock}'s javadoc, #306). Spring suspends the ambient transaction
 * for the duration of that inner one; if the insert fails, ONLY the inner, already-{@code
 * isNewTransaction()}, physical transaction is rolled back — the ambient one is resumed
 * untouched (never marked rollback-only), so {@link #regress}'s {@code catch} block runs its
 * compensating {@code episodes.delete}/{@code incidents.revertRegression} calls inside a still
 * -healthy ambient transaction, which then commits normally. Before #316, {@code AuditService}
 * called {@code repository.saveAndFlush} directly, joining (not suspending) the ambient
 * transaction — an insert failure there poisoned the SAME physical transaction the compensating
 * writes ran in, so the ambient {@code tx.executeWithoutResult} commit later detected the
 * rollback-only flag and threw {@code UnexpectedRollbackException}, undoing the compensation
 * along with everything else. This specific poison-propagation mechanism runs through a REAL
 * shared Hibernate session/JDBC connection — a hand-rolled {@code PlatformTransactionManager}
 * fake (as {@code IncidentLedgerServiceTest}'s mocked-repository tests use) cannot reproduce it
 * either way, so {@code IncidentLedgerIT} proves the CURRENT (post-#316) behavior against a
 * real Postgres/Hibernate stack: a genuine audit-insert failure, injected via a scoped trigger,
 * lands the compensating writes while the audit row itself never appears.
 */
@Service
@ConditionalOnProperty(name = "inspector.incidents.enabled", havingValue = "true", matchIfMissing = true)
public class IncidentLedgerService {

    static final String ACTION_REGRESSED = "incident-regressed";

    /** Automated sampler transition — not a human actor (the AuditRetentionPurger convention). */
    private static final String ACTOR_SYSTEM = "system";

    private static final Logger log = LoggerFactory.getLogger(IncidentLedgerService.class);

    private final IncidentRepository incidents;
    private final IncidentEpisodeRepository episodes;
    private final IncidentOccurrenceRepository occurrences;
    private final AuditService audit;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final int regressionMinCount;

    public IncidentLedgerService(
            IncidentRepository incidents,
            IncidentEpisodeRepository episodes,
            IncidentOccurrenceRepository occurrences,
            AuditService audit,
            ObjectMapper json,
            TransactionTemplate tx,
            InspectorProperties properties) {
        this.incidents = incidents;
        this.episodes = episodes;
        this.occurrences = occurrences;
        this.audit = audit;
        this.json = json;
        this.tx = tx;
        this.regressionMinCount = properties.incidentsOrDefault().regressionMinCountOrDefault();
    }

    /** The sampler seam. Failure isolation: nothing thrown here may break the snapshot cycle. */
    @EventListener
    public void onAggregationSampled(AggregationSampledEvent event) {
        try {
            ingest(event.sample(), event.bucketedInstant());
        } catch (RuntimeException e) {
            log.warn("incident-ledger cycle skipped — {}", e.toString());
        }
    }

    /**
     * One cycle: ingest every live group, then arm the zero-state gate for RESOLVED incidents
     * the cycle observed absent/zero. Package-visible so tests drive cycles deterministically.
     */
    void ingest(AggregationSample sample, Instant bucket) {
        int failed = 0;
        RuntimeException firstFailure = null;
        for (ErrorGroup group : sample.errorGroups()) {
            if (group.total() <= 0) {
                continue; // a zero group is an absence-observation — the sweep handles it
            }
            try {
                ingestGroup(group, sample, bucket);
            } catch (RuntimeException e) {
                failed++;
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        if (sample.cycleComplete()) {
            try {
                sweepZeroState(sample);
            } catch (RuntimeException e) {
                failed++;
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        } else {
            // #302: an unreachable engine this cycle means every group it owns is UNOBSERVED,
            // not absent — arming the gate here would fire a false REGRESSED on recovery. Live
            // groups above were still ingested honestly; only the absence-triggered write waits.
            log.debug("incident-ledger: skipping the zero-state sweep — this cycle did not reach every engine");
        }
        if (failed > 0) {
            // warn ONCE per cycle, never throw — store unavailability degrades to a skipped cycle
            log.warn(
                    "incident-ledger: {} step(s) skipped this cycle — first cause: {}",
                    failed,
                    firstFailure.toString());
        }
    }

    private void ingestGroup(ErrorGroup group, AggregationSample sample, Instant bucket) {
        Optional<Incident> existing =
                incidents.findBySignatureHashAndAlgoVersion(group.signatureHash(), group.algoVersion());
        boolean truncated = isTruncated(group, sample.truncatedEngineIds());
        String countsJson = toJson(group.countsByEngine());
        if (existing.isEmpty()) {
            try {
                tx.executeWithoutResult(status ->
                        insertOpen(group, sample.sampledAt(), truncated, countsJson, bucket, sample.cycleComplete()));
            } catch (DataIntegrityViolationException e) {
                // uq_incident arbiter: a concurrent first sighting won the race — next cycle updates it
                log.debug("incident insert lost a first-sighting race (benign): {}", group.signatureHash());
            }
            return;
        }
        tx.executeWithoutResult(status -> observeExisting(
                existing.get(), group, sample.sampledAt(), truncated, countsJson, bucket, sample.cycleComplete()));
    }

    /** First sighting: OPEN incident + its live episode + the first occurrence point, atomically. */
    private void insertOpen(
            ErrorGroup group,
            Instant seenAt,
            boolean truncated,
            String countsJson,
            Instant bucket,
            boolean cycleComplete) {
        Incident row = incidents.save(new Incident(
                group.signatureHash(),
                group.algoVersion(),
                group.exceptionClass(),
                group.normalizedMessage(),
                group.sampleRawMessage(),
                seenAt,
                group.total(),
                truncated,
                countsJson));
        episodes.save(new IncidentEpisode(row.getId(), IncidentState.OPEN, seenAt, group.total()));
        upsertOccurrence(row.getId(), group, bucket, truncated, cycleComplete);
    }

    private void observeExisting(
            Incident row,
            ErrorGroup group,
            Instant seenAt,
            boolean truncated,
            String countsJson,
            Instant bucket,
            boolean cycleComplete) {
        switch (row.getState()) {
            case OPEN, REGRESSED -> {
                int hit = incidents.updateObservedTotals(
                        row.getId(), row.getState().name(), seenAt, group.total(), truncated, countsJson);
                if (hit == 1) {
                    episodes.bumpLivePeak(row.getId(), group.total());
                }
                // hit == 0: an interleaved transition — skip quietly, next cycle reconciles
            }
            case RESOLVED -> {
                boolean gateOpen = row.isSeenZeroSinceResolve() && group.total() >= regressionMinCount;
                if (gateOpen) {
                    regress(row, group, seenAt, truncated, countsJson);
                } else {
                    // gate closed: the data stays honest — only the state transition waits
                    incidents.updateObservedTotals(
                            row.getId(), IncidentState.RESOLVED.name(), seenAt, group.total(), truncated, countsJson);
                }
            }
        }
        upsertOccurrence(row.getId(), group, bucket, truncated, cycleComplete);
    }

    /**
     * RESOLVED → REGRESSED: conditional transition (gate re-checked in the WHERE), new episode
     * ({@code start_state=REGRESSED}), fail-closed config-event audit recording the triggering
     * count. On audit failure the transition+episode are compensated away (ack discipline) and
     * the cycle warns — the refreshed totals survive as plain observation.
     */
    private void regress(Incident row, ErrorGroup group, Instant seenAt, boolean truncated, String countsJson) {
        int hit = incidents.transitionToRegressed(row.getId(), seenAt, group.total(), truncated, countsJson);
        if (hit != 1) {
            return; // raced with a resolve/reopen/another sampler — skip quietly
        }
        IncidentEpisode episode =
                episodes.save(new IncidentEpisode(row.getId(), IncidentState.REGRESSED, seenAt, group.total()));
        try {
            audit.recordConfigEvent(ACTION_REGRESSED, ACTOR_SYSTEM, true, regressionPayload(row, group, truncated));
        } catch (AuditUnavailableException e) {
            episodes.delete(episode);
            incidents.revertRegression(row.getId(), row.getLastRegressedAt());
            log.warn(
                    "incident regression NOT applied (audit store unavailable, fail-closed) — signature {} retries"
                            + " next cycle: {}",
                    row.getSignatureHash(),
                    e.toString());
        }
    }

    /**
     * The one absence-triggered write (INCIDENT-LEDGER §5): every RESOLVED incident whose group
     * this cycle observed absent or zero gets its zero-state gate armed. Old-generation rows
     * (orphaned by an ALGO_VERSION bump) are absent by definition and arm too — harmless: their
     * hash space is retired, so the gate can never fire for them.
     *
     * <p><b>Only ever invoked when {@link AggregationSample#cycleComplete()} is true</b> (#302):
     * "observed absent" requires the group's owning engine to have actually been reached this
     * cycle. A blind cycle (one registry engine's envelope not {@code ok()}) cannot tell absent
     * apart from unreachable, so the caller skips this method entirely rather than treat a gap
     * as a zero — arming from an unobserved engine would let a single hiccup regress every
     * RESOLVED incident behind it.
     */
    private void sweepZeroState(AggregationSample sample) {
        Set<String> live = new HashSet<>();
        for (ErrorGroup group : sample.errorGroups()) {
            if (group.total() > 0) {
                live.add(identityKey(group.signatureHash(), group.algoVersion()));
            }
        }
        for (Incident resolved : incidents.findByStateAndSeenZeroSinceResolveFalse(IncidentState.RESOLVED)) {
            if (!live.contains(identityKey(resolved.getSignatureHash(), resolved.getAlgoVersion()))) {
                incidents.markSeenZeroSinceResolve(resolved.getId()); // conditional — a miss is fine
            }
        }
    }

    /**
     * The per-cycle time-series point. BOTH honesty markers are persisted with it (V21): the
     * R-SEM-12 scan-cap {@code truncated} floor AND {@code cycleComplete} — whether every
     * registry engine was actually reached on the pass that produced these counts (#302).
     *
     * <p>Live groups deliberately still write on an incomplete cycle (their totals are honest
     * observations of the engines that DID answer), but the row must SAY so: without the marker
     * a multi-engine class's total silently drops and recovers with an outage, and every
     * downstream reader — the attention F factor's positive-delta sum, the RETRYING lane's
     * spell edges — is structurally unable to tell that apart from real movement.
     */
    private void upsertOccurrence(
            long incidentId, ErrorGroup group, Instant bucket, boolean truncated, boolean cycleComplete) {
        occurrences.upsert(
                incidentId,
                bucket,
                group.total(),
                group.deadLetterCount() != null ? group.deadLetterCount() : 0L,
                group.retryingCount() != null ? group.retryingCount() : 0L,
                truncated,
                cycleComplete);
    }

    /**
     * A group's counts are lower bounds exactly when one of its engines' failure-lane scans hit
     * the cap this pass (R-SEM-12; see {@link AggregationSample#truncatedEngineIds()}).
     */
    private static boolean isTruncated(ErrorGroup group, Set<String> truncatedEngineIds) {
        if (group.countsByEngine() == null || truncatedEngineIds.isEmpty()) {
            return false;
        }
        for (String engineId : group.countsByEngine().keySet()) {
            if (truncatedEngineIds.contains(engineId)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> regressionPayload(Incident row, ErrorGroup group, boolean truncated) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("incidentId", row.getId());
        payload.put("signatureHash", row.getSignatureHash());
        payload.put("algoVersion", row.getAlgoVersion());
        if (row.getExceptionClass() != null) {
            payload.put("exceptionClass", row.getExceptionClass());
        }
        payload.put("triggeringTotal", group.total());
        payload.put("triggeringTotalTruncated", truncated);
        payload.put("regressionMinCount", regressionMinCount);
        payload.put("regressionCount", row.getRegressionCount() + 1);
        return payload;
    }

    private String toJson(Map<String, Map<String, Long>> countsByEngine) {
        try {
            return json.writeValueAsString(countsByEngine != null ? countsByEngine : Map.of());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("counts_by_engine serialization failed", e);
        }
    }

    private static String identityKey(String signatureHash, int algoVersion) {
        return signatureHash + '#' + algoVersion;
    }
}
