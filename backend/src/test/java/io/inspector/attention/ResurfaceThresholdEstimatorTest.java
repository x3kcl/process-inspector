package io.inspector.attention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.inspector.config.InspectorProperties;
import io.inspector.incident.Incident;
import io.inspector.incident.IncidentOccurrence;
import io.inspector.incident.IncidentOccurrenceRepository;
import io.inspector.incident.IncidentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: C3 — the R-BAU-01 auto-resurface threshold's PROVENANCE (ALARM-COST-MODEL.md §3.3).
 *
 * <p>The behavior that actually ships is the first test: with the default configuration the
 * estimator answers today's hand-tuned constant verbatim, because §3.3 states the derived value
 * takes effect only after the FULL §7 gate — and G4 (≥ 10 completed ack lifecycles) stands at
 * zero acks ever recorded. Everything else here proves the opt-in path degrades to that same
 * constant rather than to a guess whenever the evidence is thin.
 *
 * <p>The replay math itself is proven independently in {@code CounterfactualAckReplayTest}; this
 * class owns the store-facing glue and the fallback ladder. {@code IncidentOccurrence} is never
 * constructed (no public constructor anywhere in this codebase — rung-4 territory).
 */
class ResurfaceThresholdEstimatorTest {

    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");

    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final IncidentOccurrenceRepository occurrences = mock(IncidentOccurrenceRepository.class);

    @Test
    void byDefaultTheThresholdIsTodaysConstantAndNoLedgerReadHappensAtAll() {
        assertThat(estimator(false, 20).thresholdPct("hash-a", 2)).isEqualTo(20);

        verifyNoInteractions(incidents, occurrences);
    }

    @Test
    void theExistingConfigKeyKeepsOverridingExactlyAsBefore() {
        assertThat(estimator(false, 35).thresholdPct("hash-a", 2)).isEqualTo(35);
    }

    @Test
    void optedInButWithNoLedgerRowFallsBackToTheConstantRatherThanDerivingFromNothing() {
        when(incidents.findBySignatureHashAndAlgoVersion(anyString(), anyInt())).thenReturn(Optional.empty());

        assertThat(estimator(true, 20).thresholdPct("hash-a", 2)).isEqualTo(20);
    }

    @Test
    void optedInButWithNoSegmentableSeriesFallsBackToTheConstant() {
        Incident row = mock(Incident.class);
        when(row.getId()).thenReturn(4L);
        when(incidents.findBySignatureHashAndAlgoVersion("hash-a", 2)).thenReturn(Optional.of(row));
        when(occurrences.findByIdIncidentIdAndIdSampledAtGreaterThanEqualOrderByIdSampledAtAsc(anyLong(), any()))
                .thenReturn(List.of());

        assertThat(estimator(true, 20).thresholdPct("hash-a", 2)).isEqualTo(20);
    }

    /* ---------------- review FIX 4: opting in can only ever be MORE conservative ---------------- */

    @Test
    void aZeroJitterClassKeepsTheConstantInsteadOfSILENTLYHalvingIt() {
        // The confirmed defect. For a low-jitter class — THE MEASURED PILOT STATE (§5.6, "CV ~ 0
        // on both live classes") — every grid candidate collapsed to max(10, k*0*100) = 10, no
        // resurface ever fired, so `falseResurfaces = 0 <= budget` held VACUOUSLY and k = 0.5 won
        // immediately. thresholdPct then returned max(floorPct, ceil(0.5*0*100)) = 10. So merely
        // OPTING IN moved every static class from a 20 % resurface threshold to a 10 % one —
        // twice as many ack interruptions — via a "fit" satisfied by having no data, while §3.3
        // sells the derived value as TIGHTENING the guard. Before the fix this asserted 10.
        seriesOf(flat(21, 40));

        assertThat(estimator(true, 20).thresholdPct("hash-a", 2)).isEqualTo(20);
    }

    @Test
    void aFittedValueBelowTodaysConstantIsFlooredAtTheConstantNeverAppliedAsIs() {
        // Second rail, independent of the first: even a class whose series DOES exercise the
        // threshold can only ever move it UP. §3.3's whole purpose is to lift the guard clear of
        // normal jitter; a derived value below today's constant would interrupt the operator more
        // often than the constant does, which is the opposite of what the doc promises.
        long[] noisy = new long[80];
        for (int i = 0; i < noisy.length; i++) {
            noisy[i] = i % 2 == 0 ? 60 : 140; // CV ~ 0.4 — fits a derived ~140 %
        }
        seriesOf(noisy);

        assertThat(estimator(true, 200).thresholdPct("hash-a", 2)).isEqualTo(200);
    }

    @Test
    void aGenuinelyNoisyClassStillEarnsItsDerivedThresholdWhenThatIsTheMoreConservativeOne() {
        long[] noisy = new long[80];
        for (int i = 0; i < noisy.length; i++) {
            noisy[i] = i % 2 == 0 ? 60 : 140;
        }
        seriesOf(noisy);

        assertThat(estimator(true, 20).thresholdPct("hash-a", 2)).isGreaterThan(20);
    }

    @Test
    void aBrokenStoreNeverMakesTheAckPolicyLessPredictable() {
        when(incidents.findBySignatureHashAndAlgoVersion(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("store down"));

        assertThat(estimator(true, 20).thresholdPct("hash-a", 2)).isEqualTo(20);
    }

    private static long[] flat(long total, int buckets) {
        long[] series = new long[buckets];
        java.util.Arrays.fill(series, total);
        return series;
    }

    /**
     * Stubs the windowed occurrence read with a synthetic series. The entity has no public
     * constructor anywhere in this codebase (rung-4 territory), so the two accessors the
     * estimator actually reads are mocked — nothing else about the row matters here.
     */
    private void seriesOf(long[] totals) {
        Incident row = mock(Incident.class);
        when(row.getId()).thenReturn(7L);
        when(incidents.findBySignatureHashAndAlgoVersion("hash-a", 2)).thenReturn(Optional.of(row));
        List<IncidentOccurrence> points = new java.util.ArrayList<>(totals.length);
        for (long total : totals) {
            IncidentOccurrence point = mock(IncidentOccurrence.class);
            when(point.getTotal()).thenReturn(total);
            points.add(point);
        }
        when(occurrences.findByIdIncidentIdAndIdSampledAtGreaterThanEqualOrderByIdSampledAtAsc(anyLong(), any()))
                .thenReturn(points);
    }

    private ResurfaceThresholdEstimator estimator(boolean derived, int constantPct) {
        InspectorProperties props = new InspectorProperties(
                null,
                null,
                new InspectorProperties.Triage(
                        null,
                        null,
                        null,
                        constantPct,
                        null,
                        new InspectorProperties.Attention(
                                null, null, null, null, null, null, null, derived, null, null)),
                null,
                null,
                null,
                null,
                null);
        return new ResurfaceThresholdEstimator(incidents, occurrences, Clock.fixed(NOW, ZoneOffset.UTC), props);
    }
}
