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
        try {
            sweepZeroState(sample);
        } catch (RuntimeException e) {
            failed++;
            if (firstFailure == null) {
                firstFailure = e;
            }
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
                tx.executeWithoutResult(status -> insertOpen(group, sample.sampledAt(), truncated, countsJson, bucket));
            } catch (DataIntegrityViolationException e) {
                // uq_incident arbiter: a concurrent first sighting won the race — next cycle updates it
                log.debug("incident insert lost a first-sighting race (benign): {}", group.signatureHash());
            }
            return;
        }
        tx.executeWithoutResult(
                status -> observeExisting(existing.get(), group, sample.sampledAt(), truncated, countsJson, bucket));
    }

    /** First sighting: OPEN incident + its live episode + the first occurrence point, atomically. */
    private void insertOpen(ErrorGroup group, Instant seenAt, boolean truncated, String countsJson, Instant bucket) {
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
        upsertOccurrence(row.getId(), group, bucket, truncated);
    }

    private void observeExisting(
            Incident row, ErrorGroup group, Instant seenAt, boolean truncated, String countsJson, Instant bucket) {
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
        upsertOccurrence(row.getId(), group, bucket, truncated);
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

    private void upsertOccurrence(long incidentId, ErrorGroup group, Instant bucket, boolean truncated) {
        occurrences.upsert(
                incidentId,
                bucket,
                group.total(),
                group.deadLetterCount() != null ? group.deadLetterCount() : 0L,
                group.retryingCount() != null ? group.retryingCount() : 0L,
                truncated);
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
