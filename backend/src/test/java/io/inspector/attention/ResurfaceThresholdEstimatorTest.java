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

    @Test
    void aBrokenStoreNeverMakesTheAckPolicyLessPredictable() {
        when(incidents.findBySignatureHashAndAlgoVersion(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("store down"));

        assertThat(estimator(true, 20).thresholdPct("hash-a", 2)).isEqualTo(20);
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
