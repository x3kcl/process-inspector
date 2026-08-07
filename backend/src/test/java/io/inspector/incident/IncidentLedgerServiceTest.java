package io.inspector.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.AuditService;
import io.inspector.audit.AuditUnavailableException;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.Incidents;
import io.inspector.dto.ErrorGroup;
import io.inspector.snapshot.AggregationSample;
import io.inspector.snapshot.AggregationSampledEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rung 1: the INCIDENT-LEDGER §5 ingestion state machine with mocked stores — first sighting,
 * totals/peak refresh, the regression zero-state gate + min-count hysteresis, the
 * absence-triggered zero-state arm, algo-generation orphaning, truncation propagation, the
 * fail-closed audit compensation, and the never-throw listener contract. Everything DB-real
 * (ON CONFLICT, conditional-UPDATE misses under a live race, Flyway/validate) is proven by
 * {@code IncidentLedgerIT}.
 */
class IncidentLedgerServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T09:00:37Z");
    private static final Instant BUCKET = Instant.parse("2026-07-18T09:00:00Z");
    private static final long ID = 42L;

    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final IncidentEpisodeRepository episodes = mock(IncidentEpisodeRepository.class);
    private final IncidentOccurrenceRepository occurrences = mock(IncidentOccurrenceRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final IncidentLedgerService service = service(1);

    /* ---------------- first sighting ---------------- */

    @Test
    void firstSightingInsertsAnOpenIncidentWithLiveEpisodeAndOccurrence() {
        stubSaveReturningId();
        when(incidents.findBySignatureHashAndAlgoVersion("hash-1", 1)).thenReturn(java.util.Optional.empty());

        service.ingest(sample(group("hash-1", 1, 7, 5, 2)), BUCKET);

        ArgumentCaptor<Incident> row = ArgumentCaptor.forClass(Incident.class);
        verify(incidents).save(row.capture());
        assertThat(row.getValue().getState()).isEqualTo(IncidentState.OPEN);
        assertThat(row.getValue().getSignatureHash()).isEqualTo("hash-1");
        assertThat(row.getValue().getAlgoVersion()).isEqualTo(1);
        assertThat(row.getValue().getFirstSeen()).isEqualTo(NOW);
        assertThat(row.getValue().getLastSeen()).isEqualTo(NOW);
        assertThat(row.getValue().getLastTotal()).isEqualTo(7);
        assertThat(row.getValue().isLastTruncated()).isFalse();
        assertThat(row.getValue().getCountsByEngine()).contains("engine-a");
        assertThat(row.getValue().isSeenZeroSinceResolve()).isFalse();
        assertThat(row.getValue().getRegressionCount()).isZero();

        ArgumentCaptor<IncidentEpisode> episode = ArgumentCaptor.forClass(IncidentEpisode.class);
        verify(episodes).save(episode.capture());
        assertThat(episode.getValue().getIncidentId()).isEqualTo(ID);
        assertThat(episode.getValue().getStartState()).isEqualTo(IncidentState.OPEN);
        assertThat(episode.getValue().getStartedAt()).isEqualTo(NOW);
        assertThat(episode.getValue().getPeakTotal()).isEqualTo(7);
        assertThat(episode.getValue().getEndedAt()).isNull();

        verify(occurrences).upsert(ID, BUCKET, 7, 5, 2, false, true, CANONICAL_FLEET);
    }

    @Test
    void algoVersionBumpCreatesANewIncidentAndOrphansTheOldGeneration() {
        stubSaveReturningId();
        // the v1 row exists, but identity is (hash, algoVersion): the v2 lookup finds nothing
        when(incidents.findBySignatureHashAndAlgoVersion("hash-1", 2)).thenReturn(java.util.Optional.empty());

        service.ingest(sample(group("hash-1", 2, 3, 3, 0)), BUCKET);

        ArgumentCaptor<Incident> row = ArgumentCaptor.forClass(Incident.class);
        verify(incidents).save(row.capture());
        assertThat(row.getValue().getAlgoVersion()).isEqualTo(2);
        // the old generation is untouched — no update ever targets it
        verify(incidents, never()).updateObservedTotals(anyLong(), anyString(), any(), anyLong(), anyBoolean(), any());
    }

    /* ---------------- live refresh ---------------- */

    @Test
    void liveGroupRefreshesTotalsAndBumpsTheLiveEpisodePeak() {
        Incident row = incident(IncidentState.OPEN, false);
        when(incidents.findBySignatureHashAndAlgoVersion("hash-1", 1)).thenReturn(java.util.Optional.of(row));
        when(incidents.updateObservedTotals(eq(ID), eq("OPEN"), eq(NOW), eq(9L), eq(false), any()))
                .thenReturn(1);

        service.ingest(sample(group("hash-1", 1, 9, 6, 3)), BUCKET);

        verify(incidents).updateObservedTotals(eq(ID), eq("OPEN"), eq(NOW), eq(9L), eq(false), any());
        verify(episodes).bumpLivePeak(ID, 9);
        verify(episodes, never()).save(any());
        verify(occurrences).upsert(ID, BUCKET, 9, 6, 3, false, true, CANONICAL_FLEET);
    }

    @Test
    void aMissedConditionalUpdateSkipsQuietlyThisCycle() {
        Incident row = incident(IncidentState.OPEN, false);
        when(incidents.findBySignatureHashAndAlgoVersion("hash-1", 1)).thenReturn(java.util.Optional.of(row));
        when(incidents.updateObservedTotals(anyLong(), anyString(), any(), anyLong(), anyBoolean(), any()))
                .thenReturn(0); // an interleaved resolve/reopen raced us

        assertThatCode(() -> service.ingest(sample(group("hash-1", 1, 9, 6, 3)), BUCKET))
                .doesNotThrowAnyException();

        verify(episodes, never()).bumpLivePeak(anyLong(), anyLong());
        // the occurrence is still an honest observation of what the cycle saw
        verify(occurrences).upsert(ID, BUCKET, 9, 6, 3, false, true, CANONICAL_FLEET);
    }

    /* ---------------- the regression gate ---------------- */

    @Test
    void resolvedWithoutAZeroStateObservationNeverRegresses_totalsStillUpdate() {
        Incident row = incident(IncidentState.RESOLVED, false); // gate closed: no post-resolve zero seen
        when(incidents.findBySignatureHashAndAlgoVersion("hash-1", 1)).thenReturn(java.util.Optional.of(row));

        service.ingest(sample(group("hash-1", 1, 8, 8, 0)), BUCKET);

        verify(incidents, never()).transitionToRegressed(anyLong(), any(), anyLong(), anyBoolean(), any());
        verify(audit, never()).recordConfigEvent(anyString(), anyString(), anyBoolean(), any());
        verify(incidents).updateObservedTotals(eq(ID), eq("RESOLVED"), eq(NOW), eq(8L), eq(false), any());
        verify(occurrences).upsert(ID, BUCKET, 8, 8, 0, false, true, CANONICAL_FLEET);
    }

    @Test
    void regressionFiresAfterZeroStateWithNewEpisodeAndAuditRecordingTheTriggeringCount() {
        stubEpisodeSaveReturningArgument();
        Incident row = incident(IncidentState.RESOLVED, true); // gate armed
        when(incidents.findBySignatureHashAndAlgoVersion("hash-1", 1)).thenReturn(java.util.Optional.of(row));
        when(incidents.transitionToRegressed(eq(ID), eq(NOW), eq(8L), eq(false), any()))
                .thenReturn(1);

        service.ingest(sample(group("hash-1", 1, 8, 8, 0)), BUCKET);

        verify(incidents).transitionToRegressed(eq(ID), eq(NOW), eq(8L), eq(false), any());
        ArgumentCaptor<IncidentEpisode> episode = ArgumentCaptor.forClass(IncidentEpisode.class);
        verify(episodes).save(episode.capture());
        assertThat(episode.getValue().getStartState()).isEqualTo(IncidentState.REGRESSED);
        assertThat(episode.getValue().getStartedAt()).isEqualTo(NOW);
        assertThat(episode.getValue().getPeakTotal()).isEqualTo(8);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .recordConfigEvent(
                        eq(IncidentLedgerService.ACTION_REGRESSED), eq("system"), eq(true), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("signatureHash", "hash-1")
                .containsEntry("triggeringTotal", 8L)
                .containsEntry("regressionCount", 1);
    }

    @Test
    void regressionMinCountGatesTheTransition_totalsStillUpdate() {
        IncidentLedgerService hysteretic = service(5);
        Incident row = incident(IncidentState.RESOLVED, true);
        when(incidents.findBySignatureHashAndAlgoVersion("hash-1", 1)).thenReturn(java.util.Optional.of(row));

        hysteretic.ingest(sample(group("hash-1", 1, 4, 4, 0)), BUCKET); // 4 < min 5

        verify(incidents, never()).transitionToRegressed(anyLong(), any(), anyLong(), anyBoolean(), any());
        verify(incidents).updateObservedTotals(eq(ID), eq("RESOLVED"), eq(NOW), eq(4L), eq(false), any());
        verify(occurrences).upsert(ID, BUCKET, 4, 4, 0, false, true, CANONICAL_FLEET);
    }

    @Test
    void aRacedRegressionTransitionSkipsQuietlyWithoutEpisodeOrAudit() {
        Incident row = incident(IncidentState.RESOLVED, true);
        when(incidents.findBySignatureHashAndAlgoVersion("hash-1", 1)).thenReturn(java.util.Optional.of(row));
        when(incidents.transitionToRegressed(anyLong(), any(), anyLong(), anyBoolean(), any()))
                .thenReturn(0); // a human reopened/re-resolved mid-cycle

        service.ingest(sample(group("hash-1", 1, 8, 8, 0)), BUCKET);

        verify(episodes, never()).save(any());
        verify(audit, never()).recordConfigEvent(anyString(), anyString(), anyBoolean(), any());
        verify(occurrences).upsert(ID, BUCKET, 8, 8, 0, false, true, CANONICAL_FLEET);
    }

    @Test
    void auditFailureCompensatesTheRegressionAndNeverThrows() {
        stubEpisodeSaveReturningArgument();
        Instant previousRegression = Instant.parse("2026-07-01T00:00:00Z");
        Incident row = incident(IncidentState.RESOLVED, true);
        when(row.getLastRegressedAt()).thenReturn(previousRegression);
        when(incidents.findBySignatureHashAndAlgoVersion("hash-1", 1)).thenReturn(java.util.Optional.of(row));
        when(incidents.transitionToRegressed(anyLong(), any(), anyLong(), anyBoolean(), any()))
                .thenReturn(1);
        doThrow(new AuditUnavailableException(new RuntimeException("audit db down")))
                .when(audit)
                .recordConfigEvent(anyString(), anyString(), anyBoolean(), any());

        assertThatCode(() -> service.ingest(sample(group("hash-1", 1, 8, 8, 0)), BUCKET))
                .doesNotThrowAnyException();

        // fail-closed (the ack discipline): the transition is undone, the counters restored
        verify(episodes).delete(any(IncidentEpisode.class));
        verify(incidents).revertRegression(ID, previousRegression);
    }

    /* ---------------- the zero-state sweep ---------------- */

    @Test
    void absentOrZeroGroupsArmTheZeroStateGateOnResolvedIncidents() {
        Incident absent = incident(IncidentState.RESOLVED, false);
        Incident zero = mock(Incident.class);
        when(zero.getId()).thenReturn(77L);
        when(zero.getSignatureHash()).thenReturn("hash-zero");
        when(zero.getAlgoVersion()).thenReturn(1);
        when(incidents.findByStateAndSeenZeroSinceResolveFalse(IncidentState.RESOLVED))
                .thenReturn(List.of(absent, zero));
        stubLastObservedFleet(CANONICAL_FLEET, ID, 77L); // both last seen under THIS pass's fleet

        // "hash-zero" IS in the sample but with total 0 — an absence-observation, not a live group
        service.ingest(sample(group("hash-zero", 1, 0, 0, 0)), BUCKET);

        verify(incidents).markSeenZeroSinceResolve(ID);
        verify(incidents).markSeenZeroSinceResolve(77L);
        // a zero group must never create or refresh an incident
        verify(incidents, never()).save(any());
        verify(occurrences, never())
                .upsert(anyLong(), any(), anyLong(), anyLong(), anyLong(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void aLiveGroupNeverArmsItsResolvedIncidentsZeroStateGate() {
        Incident row = incident(IncidentState.RESOLVED, false);
        when(incidents.findBySignatureHashAndAlgoVersion("hash-1", 1)).thenReturn(java.util.Optional.of(row));
        when(incidents.findByStateAndSeenZeroSinceResolveFalse(IncidentState.RESOLVED))
                .thenReturn(List.of(row));

        service.ingest(sample(group("hash-1", 1, 8, 8, 0)), BUCKET);

        verify(incidents, never()).markSeenZeroSinceResolve(anyLong());
    }

    /* ---------------- #302: the blind-cycle gate ---------------- */

    @Test
    void aBlindCycleLeavesAResolvedIncidentsZeroStateGateUnarmed() {
        // findByStateAndSeenZeroSinceResolveFalse is deliberately NOT stubbed: a blind cycle must
        // skip the sweep before ever calling it — if it were invoked anyway, Mockito's null-list
        // default would NPE here and fail the test, which is exactly the signal we want.

        // one engine's slice failed this cycle (cycleComplete=false) and the group is entirely
        // absent from the sample — the false-positive scenario from #302: an unreachable engine
        // must never be read as "the class resolved".
        service.ingest(sample(false), BUCKET);

        verify(incidents, never()).markSeenZeroSinceResolve(anyLong());
        verify(incidents, never()).findByStateAndSeenZeroSinceResolveFalse(any());
    }

    @Test
    void aGenuinelyCompleteCycleWithTheGroupAbsentStillArmsTheGate() {
        Incident resolved = incident(IncidentState.RESOLVED, false);
        when(incidents.findByStateAndSeenZeroSinceResolveFalse(IncidentState.RESOLVED))
                .thenReturn(List.of(resolved));
        // #380's must-not-change half: the class was last observed under THIS pass's fleet, so
        // the absence is attributable to an observation and the gate arms exactly as it always
        // has. (Its rung-4 twin — same arc against real Postgres, green on the pre-fix base —
        // is IncidentLedgerIT.aGenuineDrainAndReturnOnAStableFleetStillRegresses.)
        stubLastObservedFleet(CANONICAL_FLEET, ID);

        // every registry engine was reached (cycleComplete=true) and the group is genuinely
        // absent — this IS an observed-zero, so the gate arms.
        service.ingest(sample(true), BUCKET);

        verify(incidents).markSeenZeroSinceResolve(ID);
    }

    @Test
    void aBlindCycleStillIngestsLiveGroupsHonestly() {
        stubSaveReturningId();
        when(incidents.findBySignatureHashAndAlgoVersion("hash-1", 1)).thenReturn(java.util.Optional.empty());

        // a live group ingests normally even on an incomplete cycle — only the absence-triggered
        // sweep is gated on cycleComplete, per INCIDENT-LEDGER §5 (#302 scope).
        service.ingest(sample(false, group("hash-1", 1, 7, 5, 2)), BUCKET);

        verify(incidents).save(any(Incident.class));
        // ...but the row SAYS it was blind (V21): the counts may be missing an unreachable
        // engine's members, and every downstream reader needs to know that. Ingesting the row
        // while dropping the marker is what turned an outage into phantom arrivals and into
        // fabricated SELF_HEALED spells.
        verify(occurrences).upsert(ID, BUCKET, 7, 5, 2, false, false, CANONICAL_FLEET);
    }

    @Test
    void aBlindCycleMarksTheOccurrenceRowOfAnAlreadyKnownClassToo() {
        Incident row = incident(IncidentState.OPEN, false);
        when(incidents.findBySignatureHashAndAlgoVersion("hash-1", 1)).thenReturn(java.util.Optional.of(row));
        when(incidents.updateObservedTotals(anyLong(), anyString(), any(), anyLong(), anyBoolean(), any()))
                .thenReturn(1);

        service.ingest(sample(false, group("hash-1", 1, 9, 6, 3)), BUCKET);

        verify(occurrences).upsert(ID, BUCKET, 9, 6, 3, false, false, CANONICAL_FLEET);
    }

    /* ---------------- #380: the sweep may only arm on an OBSERVED absence ---------------- */

    @Test
    void aFleetChangeSinceTheClassWasLastObservedNeverArmsTheZeroStateGate() {
        Incident resolved = incident(IncidentState.RESOLVED, false);
        when(incidents.findByStateAndSeenZeroSinceResolveFalse(IncidentState.RESOLVED))
                .thenReturn(List.of(resolved));
        // The class was last OBSERVED under {engine-7, engine-a}. `occurrences` is left unstubbed
        // for the last-observed-scope lookup, i.e. "this cycle cannot establish the scope the
        // class was last seen under" — the fail-closed input.

        // This cycle fanned out over {engine-a} ALONE: engine-7 was DISABLED, so it never entered
        // registry.all(), nobody failed to answer, and cycleComplete is honestly TRUE for the
        // shrunken scope. The class is absent — from a fleet that never hosted it. #380: that
        // absence was never observed, so it is not evidence of absence.
        service.ingest(new AggregationSample(List.of(), List.of(), NOW, Set.of(), true, Set.of("engine-a")), BUCKET);

        verify(incidents, never()).markSeenZeroSinceResolve(anyLong());
    }

    @Test
    void aClassLastObservedUnderADifferentFleetIsNotArmedEvenOnACompleteCycle() {
        Incident resolved = incident(IncidentState.RESOLVED, false);
        when(incidents.findByStateAndSeenZeroSinceResolveFalse(IncidentState.RESOLVED))
                .thenReturn(List.of(resolved));
        // stated explicitly this time: the class's newest occurrence row says it was last seen
        // under {engine-7, engine-a} — the fleet BEFORE the disable.
        stubLastObservedFleet("engine-7,engine-a", ID);

        service.ingest(new AggregationSample(List.of(), List.of(), NOW, Set.of(), true, Set.of("engine-a")), BUCKET);

        verify(incidents, never()).markSeenZeroSinceResolve(anyLong());
    }

    @Test
    void aClassWhoseLastObservationRecordedNoScopeIsNotArmed() {
        Incident resolved = incident(IncidentState.RESOLVED, false);
        when(incidents.findByStateAndSeenZeroSinceResolveFalse(IncidentState.RESOLVED))
                .thenReturn(List.of(resolved));
        // a pre-V22 row: scope was never recorded, so it is comparable to NOTHING — not even to
        // an identical-looking unrecorded scope. V21's fail-closed rule, applied unchanged.
        stubLastObservedFleet("", ID);

        service.ingest(sample(true), BUCKET);

        verify(incidents, never()).markSeenZeroSinceResolve(anyLong());
    }

    @Test
    void aFleetEmptyCycleArmsNoResolvedIncident() {
        Incident absent = incident(IncidentState.RESOLVED, false);
        Incident other = mock(Incident.class);
        when(other.getId()).thenReturn(77L);
        when(other.getSignatureHash()).thenReturn("hash-zero");
        when(other.getAlgoVersion()).thenReturn(1);
        when(incidents.findByStateAndSeenZeroSinceResolveFalse(IncidentState.RESOLVED))
                .thenReturn(List.of(absent, other));

        // #380, the adjacent case: zero enabled engines. Nothing was observed at all, so the
        // pass's scope is UNRECORDED and it can vouch for no class's absence — arming here would
        // regress EVERY resolved incident the moment the fleet came back.
        service.ingest(new AggregationSample(List.of(), List.of(), NOW, Set.of(), true, Set.of()), BUCKET);

        verify(incidents, never()).markSeenZeroSinceResolve(anyLong());
    }

    @Test
    void aFleetEmptyCycleDoesNotEvenAskTheStoreForCandidates() {
        // findByStateAndSeenZeroSinceResolveFalse is deliberately NOT stubbed, exactly as in the
        // blind-cycle test: a cycle that cannot vouch for ANY absence must bail before the fetch.
        service.ingest(new AggregationSample(List.of(), List.of(), NOW, Set.of(), true, Set.of()), BUCKET);

        verify(incidents, never()).findByStateAndSeenZeroSinceResolveFalse(any());
        verify(incidents, never()).markSeenZeroSinceResolve(anyLong());
    }

    /* ---------------- truncation honesty ---------------- */

    @Test
    void truncationFlagsPropagateIntoIncidentAndOccurrence() {
        stubSaveReturningId();
        when(incidents.findBySignatureHashAndAlgoVersion("hash-1", 1)).thenReturn(java.util.Optional.empty());

        ErrorGroup group = group("hash-1", 1, 500, 500, 0); // engine-a's scan hit the cap
        service.ingest(new AggregationSample(List.of(), List.of(group), NOW, Set.of("engine-a"), true, FLEET), BUCKET);

        ArgumentCaptor<Incident> row = ArgumentCaptor.forClass(Incident.class);
        verify(incidents).save(row.capture());
        assertThat(row.getValue().isLastTruncated()).isTrue();
        verify(occurrences).upsert(ID, BUCKET, 500, 500, 0, true, true, CANONICAL_FLEET);
    }

    @Test
    void aTruncatedEngineOutsideTheGroupDoesNotFloorIt() {
        stubSaveReturningId();
        when(incidents.findBySignatureHashAndAlgoVersion("hash-1", 1)).thenReturn(java.util.Optional.empty());

        ErrorGroup group = group("hash-1", 1, 7, 5, 2); // lives on engine-a only
        service.ingest(new AggregationSample(List.of(), List.of(group), NOW, Set.of("engine-b"), true, FLEET), BUCKET);

        ArgumentCaptor<Incident> row = ArgumentCaptor.forClass(Incident.class);
        verify(incidents).save(row.capture());
        assertThat(row.getValue().isLastTruncated()).isFalse();
        verify(occurrences).upsert(ID, BUCKET, 7, 5, 2, false, true, CANONICAL_FLEET);
    }

    /* ---------------- occurrence bucketing + failure isolation ---------------- */

    @Test
    void twoCyclesInOneBucketUpsertTheSameOccurrenceKey() {
        Incident row = incident(IncidentState.OPEN, false);
        when(incidents.findBySignatureHashAndAlgoVersion("hash-1", 1)).thenReturn(java.util.Optional.of(row));
        when(incidents.updateObservedTotals(anyLong(), anyString(), any(), anyLong(), anyBoolean(), any()))
                .thenReturn(1);

        service.ingest(sample(group("hash-1", 1, 7, 5, 2)), BUCKET);
        service.ingest(sample(group("hash-1", 1, 9, 7, 2)), BUCKET);

        // same (incident, bucket) key both times — the DB's ON CONFLICT keeps it one row (IT-proven)
        verify(occurrences).upsert(ID, BUCKET, 7, 5, 2, false, true, CANONICAL_FLEET);
        verify(occurrences).upsert(ID, BUCKET, 9, 7, 2, false, true, CANONICAL_FLEET);
        verify(occurrences, times(2))
                .upsert(eq(ID), eq(BUCKET), anyLong(), anyLong(), anyLong(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void theListenerNeverThrows_storeFailureDegradesToAWarnedSkip() {
        when(incidents.findBySignatureHashAndAlgoVersion(anyString(), anyInt()))
                .thenThrow(new RuntimeException("store down"));

        assertThatCode(() -> service.onAggregationSampled(
                        new AggregationSampledEvent(sample(group("hash-1", 1, 7, 5, 2)), BUCKET)))
                .doesNotThrowAnyException();
    }

    @Test
    void onePoisonedGroupDoesNotStopTheOthers() {
        stubSaveReturningId();
        when(incidents.findBySignatureHashAndAlgoVersion("hash-bad", 1)).thenThrow(new RuntimeException("poisoned"));
        when(incidents.findBySignatureHashAndAlgoVersion("hash-good", 1)).thenReturn(java.util.Optional.empty());

        service.ingest(sample(group("hash-bad", 1, 3, 3, 0), group("hash-good", 1, 5, 5, 0)), BUCKET);

        verify(incidents).save(any(Incident.class)); // hash-good landed despite hash-bad
    }

    /* ---------------- fixtures ---------------- */

    private IncidentLedgerService service(int regressionMinCount) {
        return new IncidentLedgerService(
                incidents,
                episodes,
                occurrences,
                audit,
                new ObjectMapper(),
                noOpTx(),
                new InspectorProperties(
                        null,
                        null,
                        null,
                        null,
                        null,
                        new Incidents(true, null, regressionMinCount, null, null),
                        List.of()));
    }

    /** Rung-1 stand-in: transaction semantics themselves are proven DB-side in the IT. */
    private static TransactionTemplate noOpTx() {
        return new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {}

            @Override
            protected void doCommit(DefaultTransactionStatus status) {}

            @Override
            protected void doRollback(DefaultTransactionStatus status) {}
        });
    }

    private void stubSaveReturningId() {
        Incident saved = mock(Incident.class);
        when(saved.getId()).thenReturn(ID);
        when(incidents.save(any(Incident.class))).thenReturn(saved);
    }

    /** #380: the scope each named incident's NEWEST occurrence row recorded. */
    private void stubLastObservedFleet(String fleet, long... incidentIds) {
        List<Object[]> rows = new java.util.ArrayList<>();
        for (long incidentId : incidentIds) {
            rows.add(new Object[] {incidentId, fleet});
        }
        when(occurrences.lastObservedFleets(any())).thenReturn(rows);
    }

    private void stubEpisodeSaveReturningArgument() {
        when(episodes.save(any(IncidentEpisode.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** A mocked persisted row — the entity is setter-less by design, so states are stubbed. */
    private static Incident incident(IncidentState state, boolean seenZeroSinceResolve) {
        Incident row = mock(Incident.class);
        when(row.getId()).thenReturn(ID);
        when(row.getSignatureHash()).thenReturn("hash-1");
        when(row.getAlgoVersion()).thenReturn(1);
        when(row.getState()).thenReturn(state);
        when(row.isSeenZeroSinceResolve()).thenReturn(seenZeroSinceResolve);
        when(row.getRegressionCount()).thenReturn(0);
        return row;
    }

    /** The steady observation SCOPE (#372/V22) every fixture here ingests under. */
    private static final Set<String> FLEET = Set.of("engine-b", "engine-a");

    /** Its canonical persisted form: sorted lexicographically, comma-joined. */
    private static final String CANONICAL_FLEET = "engine-a,engine-b";

    /**
     * Production ALWAYS states its scope ({@code PollingSnapshotSource} fills
     * {@code fleetEngineIds} from the envelope it iterates), so the shared fixture does too —
     * the #380 gate only bites on a composition CHANGE or an unrecorded scope, both of which
     * the tests that care about them stage explicitly.
     */
    private static AggregationSample sample(ErrorGroup... groups) {
        return new AggregationSample(List.of(), List.of(groups), NOW, Set.of(), true, FLEET);
    }

    private static AggregationSample sample(boolean cycleComplete, ErrorGroup... groups) {
        return new AggregationSample(List.of(), List.of(groups), NOW, Set.of(), cycleComplete, FLEET);
    }

    private static ErrorGroup group(String hash, int algoVersion, long total, long deadLetters, long retrying) {
        return new ErrorGroup(
                hash,
                algoVersion,
                "java.net.SocketTimeoutException",
                "timeout after # ms",
                "timeout after 5000 ms",
                total,
                deadLetters,
                retrying,
                Map.of("engine-a", Map.of("order:v3", total)));
    }
}
