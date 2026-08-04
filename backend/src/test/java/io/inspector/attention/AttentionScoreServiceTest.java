package io.inspector.attention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.inspector.config.InspectorProperties;
import io.inspector.dto.AttentionScore;
import io.inspector.dto.SelfHealStats;
import io.inspector.incident.Incident;
import io.inspector.incident.IncidentEpisodeRepository;
import io.inspector.incident.IncidentOccurrenceRepository;
import io.inspector.incident.IncidentRepository;
import io.inspector.selfheal.SelfHealLane;
import io.inspector.selfheal.SelfHealStatsService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: {@code AttentionScoreService}'s JPA-facing glue with mocked stores — flag gating, the
 * three-aggregate model build, driver-type coercion, and degrade-safety. The MATH is proven
 * independently against synthetic inputs ({@code AttentionScoreCalculatorTest},
 * {@code AttentionRationaleTest}, {@code CounterfactualAckReplayTest}), and the neutrality
 * guarantee has its own dedicated class ({@code AttentionOrderingNeutralityTest}).
 *
 * <p>{@code IncidentOccurrence} is deliberately never constructed here — it has no public
 * constructor anywhere in this codebase (JPA hydration only), so real occurrence-series behavior
 * is rung-4 territory ({@code IncidentLedgerIT}), exactly as {@code SelfHealStatsServiceTest}
 * records. The service reads occurrences through a DB-side AGGREGATE, which is mocked at its
 * {@code Object[]} boundary below.
 */
class AttentionScoreServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");

    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final IncidentEpisodeRepository episodes = mock(IncidentEpisodeRepository.class);
    private final IncidentOccurrenceRepository occurrences = mock(IncidentOccurrenceRepository.class);
    private final SelfHealStatsService selfHeal = mock(SelfHealStatsService.class);

    @Test
    void withTheFlagOffTheServiceIsInertAndTouchesNothing() {
        AttentionScoreService service = service(false);

        assertThat(service.enabled()).isFalse();
        assertThat(service.forClass("hash-a", 2, 21, null)).isNull();
        verifyNoInteractions(incidents, episodes, occurrences, selfHeal);
    }

    @Test
    void theModelJoinsArrivalsAndClosedEpisodesOntoTheLedgerRowsByIncidentId() {
        List<Incident> ledger = List.of(incident(4L, "hash-a", NOW.minusSeconds(86_400)));
        when(incidents.findByLastSeenGreaterThanEqual(any())).thenReturn(ledger);
        when(occurrences.arrivalsSince(any())).thenReturn(List.<Object[]>of(row(4L, 7L)));
        when(episodes.closedEpisodeDurationSeconds())
                .thenReturn(List.<Object[]>of(row(4L, 3_600L), row(4L, 5_400L), row(4L, 7_200L)));

        AttentionScore scored = service(true).forClass("hash-a", 2, 21, null);

        assertThat(scored.factors().arrivals28d()).isEqualTo(7);
        assertThat(scored.factors().frequency()).isEqualTo(3.0); // log2(1+7)
        assertThat(scored.factors().ageSeconds()).isEqualTo(86_400);
        assertThat(scored.factors().recency()).isEqualTo(0.5);
        assertThat(scored.factors().closedEpisodes()).isEqualTo(3);
        assertThat(scored.factors().medianMttrSeconds()).isEqualTo(5_400L);
        assertThat(scored.factors().mttr()).isEqualTo(1.0); // this class IS the whole fleet
        assertThat(scored.suggestedAckExpirySeconds()).isEqualTo(7_200L);
    }

    @Test
    void theFleetMedianIsTakenAcrossEveryClosedEpisodeInTheLedgerNotJustThisClassSince() {
        List<Incident> ledger = List.of(incident(4L, "hash-a", NOW), incident(5L, "hash-b", NOW));
        when(incidents.findByLastSeenGreaterThanEqual(any())).thenReturn(ledger);
        when(occurrences.arrivalsSince(any())).thenReturn(List.of());
        when(episodes.closedEpisodeDurationSeconds())
                .thenReturn(List.<Object[]>of(
                        row(4L, 7_200L),
                        row(4L, 7_200L),
                        row(4L, 7_200L),
                        row(5L, 900L),
                        row(5L, 900L),
                        row(5L, 900L)));

        // Fleet median over {900,900,900,7200,7200,7200} = 900 (nearest rank).
        assertThat(service(true).forClass("hash-a", 2, 21, null).factors().mttr())
                .isEqualTo(2.0); // 7200/900 = 8, clamped to the ceiling
        assertThat(service(true).forClass("hash-b", 2, 8, null).factors().mttr())
                .isEqualTo(1.0);
    }

    @Test
    void nativeAggregatesAreCoercedWhateverNumericTypeTheDriverHandsBack() {
        List<Incident> ledger = List.of(incident(4L, "hash-a", NOW));
        when(incidents.findByLastSeenGreaterThanEqual(any())).thenReturn(ledger);
        when(occurrences.arrivalsSince(any())).thenReturn(List.<Object[]>of(new Object[] {BigInteger.valueOf(4), 15L}));
        when(episodes.closedEpisodeDurationSeconds())
                .thenReturn(List.<Object[]>of(new Object[] {BigDecimal.valueOf(4), BigDecimal.valueOf(60.7)}));

        AttentionScore scored = service(true).forClass("hash-a", 2, 21, null);

        assertThat(scored.factors().arrivals28d()).isEqualTo(15);
        assertThat(scored.factors().closedEpisodes()).isEqualTo(1);
    }

    @Test
    void anUnknownClassScoresNeutrallyRatherThanBeingBuried() {
        List<Incident> ledger = List.of(incident(4L, "hash-a", NOW));
        when(incidents.findByLastSeenGreaterThanEqual(any())).thenReturn(ledger);
        when(occurrences.arrivalsSince(any())).thenReturn(List.of());
        when(episodes.closedEpisodeDurationSeconds()).thenReturn(List.of());

        AttentionScore scored = service(true).forClass("hash-never-seen", 2, 8, null);

        assertThat(scored.factors().recency()).isEqualTo(1.0);
        assertThat(scored.factors().mttr()).isEqualTo(1.0);
        assertThat(scored.factors().selfHeal()).isEqualTo(1.0);
        assertThat(scored.factors().insufficientHistory()).isTrue();
    }

    @Test
    void theR2StatisticIsConsumedAsGivenAndNeverRecomputedHere() {
        List<Incident> ledger = List.of(incident(4L, "hash-a", NOW));
        when(incidents.findByLastSeenGreaterThanEqual(any())).thenReturn(ledger);
        when(occurrences.arrivalsSince(any())).thenReturn(List.<Object[]>of(row(4L, 3L)));
        when(episodes.closedEpisodeDurationSeconds()).thenReturn(List.of());
        SelfHealStats stats =
                new SelfHealStats(SelfHealLane.SELF_HEAL_LIKELY.name(), 14, 12, 0.72, 0.98, 300L, 480L, 2, false);

        AttentionScore scored = service(true).forClass("hash-a", 2, 21, stats);

        assertThat(scored.factors().selfHealLane()).isEqualTo("SELF_HEAL_LIKELY");
        assertThat(scored.factors().selfHeal()).isEqualTo(0.25);
        assertThat(scored.rationale()).contains("usually self-heals (12/14)");
    }

    @Test
    void aBrokenStoreYieldsNoScoreRatherThanABrokenRead() {
        when(incidents.findByLastSeenGreaterThanEqual(any())).thenThrow(new IllegalStateException("store down"));

        assertThat(service(true).forClass("hash-a", 2, 21, null)).isNull();
    }

    @Test
    void aPoisonedSelfHealStatisticDemotesNothing() {
        List<Incident> ledger = List.of(incident(4L, "hash-a", NOW));
        when(incidents.findByLastSeenGreaterThanEqual(any())).thenReturn(ledger);
        when(occurrences.arrivalsSince(any())).thenReturn(List.<Object[]>of(row(4L, 1L)));
        when(episodes.closedEpisodeDurationSeconds()).thenReturn(List.of());
        when(selfHeal.get(anyString(), anyInt())).thenThrow(new IllegalStateException("boom"));

        // forClass takes the statistic as an argument; the dashboard path resolves it itself and
        // must swallow a failure into the NEUTRAL factor rather than into a demotion.
        assertThat(service(true).forClass("hash-a", 2, 21, null).factors().selfHeal())
                .isEqualTo(1.0);
    }

    /* ---------------- fixtures ---------------- */

    private AttentionScoreService service(boolean attentionOrdering) {
        InspectorProperties props = new InspectorProperties(
                null,
                null,
                new InspectorProperties.Triage(null, null, null, null, attentionOrdering, null),
                null,
                null,
                null,
                null,
                null);
        return new AttentionScoreService(
                incidents, episodes, occurrences, selfHeal, Clock.fixed(NOW, ZoneOffset.UTC), props);
    }

    private static Incident incident(long id, String hash, Instant lastSeen) {
        Incident row = mock(Incident.class);
        when(row.getId()).thenReturn(id);
        when(row.getSignatureHash()).thenReturn(hash);
        when(row.getAlgoVersion()).thenReturn(2);
        when(row.getLastSeen()).thenReturn(lastSeen);
        return row;
    }

    private static Object[] row(long incidentId, long value) {
        return new Object[] {incidentId, value};
    }
}
