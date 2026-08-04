package io.inspector.attention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.config.InspectorProperties;
import io.inspector.dto.ErrorGroup;
import io.inspector.dto.ErrorGroupAcknowledgement;
import io.inspector.dto.TriageDashboardResponse;
import io.inspector.incident.IncidentEpisodeRepository;
import io.inspector.incident.IncidentOccurrenceRepository;
import io.inspector.incident.IncidentRepository;
import io.inspector.selfheal.SelfHealStatsService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * <b>The single most important test in slice #353.</b>
 *
 * <p>ALARM-COST-MODEL.md ships the attention score with an honest null result: replayed against
 * ALL 21,229 recorded pilot buckets the score reproduced today's count-only ordering EXACTLY
 * (§5.5 — Kendall tau = 1.0, 0 of 2 top-N positions changed), because every discriminating factor
 * is data-starved (0 closed episodes ⇒ M inert, no R2 statistics ⇒ S inert, both classes
 * continuously live ⇒ R inert). The §7 data-maturity gate is measured NOT MET on 0 of 5 axes.
 * The owner's decision was therefore to build anyway, flag-off, with PROVABLY neutral behavior —
 * and this class is that proof.
 *
 * <p>Two properties, in strengthening order:
 *
 * <ol>
 *   <li><b>Flag off ⇒ absolute inertness.</b> The decorator hands back the VERY SAME object, runs
 *       no query at all, and serializes byte-for-byte identically — so the shipped default cannot
 *       reorder, cannot add a field, and cannot cost a round trip.
 *   <li><b>Flag ON with no ledger history ⇒ still count-only.</b> The §4.1 guarantee that neutral
 *       factors can never make the landing worse than today: every factor degrades to a
 *       multiplicative identity, every score is 0.0, and the comparison falls through to the
 *       count-only tie-break. This is the property the pilot measurement actually observed.
 * </ol>
 *
 * <p>Fixtures are SYNTHETIC and deliberately so: no fixture here implies the score reorders
 * anything real today, because measured against real pilot data it does not.
 */
class AttentionOrderingNeutralityTest {

    private static final Instant NOW = Instant.parse("2026-08-04T07:05:00Z");

    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final IncidentEpisodeRepository episodes = mock(IncidentEpisodeRepository.class);
    private final IncidentOccurrenceRepository occurrences = mock(IncidentOccurrenceRepository.class);
    private final SelfHealStatsService selfHeal = mock(SelfHealStatsService.class);
    private final ObjectMapper json = new ObjectMapper();

    /* ---------------- property 1: the shipped default is absolutely inert ---------------- */

    @Test
    void withTheFlagOffTheDashboardIsReturnedAsTheVerySameObject() {
        TriageDashboardResponse response = dashboard(group("h-a", 300), group("h-b", 21), group("h-c", 8));

        TriageDashboardResponse decorated = service(false).decorate(response);

        // Identity, not equality: nothing was copied, so nothing COULD have been reordered.
        assertThat(decorated).isSameAs(response);
    }

    @Test
    void withTheFlagOffTheSerializedPayloadIsByteForByteIdenticalAndCarriesNoAttentionBlock() throws Exception {
        TriageDashboardResponse response = dashboard(group("h-a", 300), group("h-b", 21), group("h-c", 8));
        String before = json.writeValueAsString(response);

        String after = json.writeValueAsString(service(false).decorate(response));

        assertThat(after).isEqualTo(before);
        assertThat(after).doesNotContain("attention");
    }

    @Test
    void withTheFlagOffNotOneQueryRuns() {
        service(false).decorate(dashboard(group("h-a", 300), group("h-b", 21)));

        verifyNoInteractions(incidents, episodes, occurrences, selfHeal);
    }

    @Test
    void withTheFlagOffTheOrderSurvivesEveryRandomisedCorpusUntouched() {
        Random random = new Random(353); // fixed seed: a failure here must be reproducible
        AttentionScoreService service = service(false);
        for (int round = 0; round < 200; round++) {
            List<ErrorGroup> groups = randomGroups(random, 1 + random.nextInt(12));
            List<String> before = hashesOf(groups);

            List<ErrorGroup> after = service.decorate(dashboard(groups)).errorGroups();

            assertThat(hashesOf(after)).containsExactlyElementsOf(before);
        }
    }

    /* ---------------- property 2: flag ON, empty ledger ⇒ still count-only ---------------- */

    @Test
    void withTheFlagOnAndAnEmptyLedgerTheOrderIsExactlyTodaysCountOnlyOrder() {
        emptyLedger();
        List<ErrorGroup> countOnly = countOnlyOrder(
                List.of(group("h-c", 8), group("h-a", 300), group("h-b", 21), group("h-d", 1), group("h-e", 57)));

        List<ErrorGroup> ordered = service(true).decorate(dashboard(countOnly)).errorGroups();

        assertThat(hashesOf(ordered)).containsExactlyElementsOf(hashesOf(countOnly));
    }

    @Test
    void withTheFlagOnAndAnEmptyLedgerEveryScoreIsTheNeutralZeroWithAnHonestRationale() {
        emptyLedger();

        List<ErrorGroup> ordered = service(true)
                .decorate(dashboard(group("h-a", 21), group("h-b", 8)))
                .errorGroups();

        assertThat(ordered).allSatisfy(group -> {
            assertThat(group.attention()).isNotNull();
            assertThat(group.attention().score()).isZero();
            assertThat(group.attention().factors().recency()).isEqualTo(1.0);
            assertThat(group.attention().factors().mttr()).isEqualTo(1.0);
            assertThat(group.attention().factors().selfHeal()).isEqualTo(1.0);
            assertThat(group.attention().factors().insufficientHistory()).isTrue();
            // #365: no history ⇒ empty bins ⇒ the gate CANNOT fire, and an empty bin is a measured
            // quiet rather than an unknown one (§4.1a: same shape as F2's "no in-window row").
            assertThat(group.attention().factors().frequency()).isZero();
            assertThat(group.attention().factors().flooding()).isFalse();
            assertThat(group.attention().factors().burstArrivals()).isZero();
            assertThat(group.attention().factors().burstUnknown()).isFalse();
            assertThat(group.attention().factors().discardedBurstSamples()).isZero();
            assertThat(group.attention().factors().burstWindowSeconds()).isEqualTo(600);
            assertThat(group.attention().rationale())
                    .contains("no resolve-time history")
                    .contains("no self-heal history")
                    .doesNotContain("spiking")
                    .doesNotContain("recent arrival rate unknown");
            assertThat(group.attention().suggestedAckExpirySeconds()).isNull();
        });
    }

    @Test
    void aSubOnsetBurstReordersNOTHINGBecauseTheAmendmentIsInertBelowTheISAFloodThreshold() {
        // The #365 amendment's own neutrality claim, at the SERVICE level rather than the
        // calculator's: a fleet whose classes all had SOME recent arrivals — but none reaching
        // ISA-18.2's onset — must still land in exactly the order the shipped formula produced.
        Random random = new Random(365);
        for (int round = 0; round < 200; round++) {
            List<ErrorGroup> groups = distinctTotalGroups(random, 1 + random.nextInt(8));
            List<String> shipped = orderedBy(groups, history -> subOnsetBurst(history, 0));
            List<String> amended = orderedBy(groups, history -> subOnsetBurst(history, 1 + random.nextInt(9)));

            assertThat(amended).containsExactlyElementsOf(shipped);
        }
    }

    /** Scores a corpus through the real calculator with per-class evidence, then orders it. */
    private static List<String> orderedBy(
            List<ErrorGroup> groups, java.util.function.Function<Long, ClassHistory> evidence) {
        List<ErrorGroup> scored = groups.stream()
                .map(group -> group.withAttention(AttentionScoreCalculator.score(
                        group.total(), evidence.apply(group.total()), null, null, AttentionConfig.defaults(), NOW)))
                .toList();
        return hashesOf(AttentionOrdering.order(scored));
    }

    /** Arrivals proportional to the class, of which {@code burst} landed inside W — sub-onset. */
    private static ClassHistory subOnsetBurst(long total, long burst) {
        return new ClassHistory(NOW, total, false, 0L, burst, 0L, false, 0L, List.of());
    }

    @Test
    void withTheFlagOnAndAnEmptyLedgerEveryRandomisedCorpusStillLandsInCountOnlyOrder() {
        emptyLedger();
        Random random = new Random(348);
        AttentionScoreService service = service(true);
        for (int round = 0; round < 500; round++) {
            // Distinct totals: where today's stable sort leaves ties in arbitrary scan order,
            // "identical ordering" is not even well defined — ties get their own test below.
            List<ErrorGroup> countOnly = countOnlyOrder(distinctTotalGroups(random, 1 + random.nextInt(10)));

            List<ErrorGroup> ordered = service.decorate(dashboard(countOnly)).errorGroups();

            assertThat(hashesOf(ordered)).containsExactlyElementsOf(hashesOf(countOnly));
        }
    }

    @Test
    void tiedCardsGetTheDeterministicSignatureOrderRatherThanWhicheverEngineWasScannedFirst() {
        emptyLedger();
        AttentionScoreService service = service(true);
        List<ErrorGroup> scannedOneWay = List.of(group("h-z", 8), group("h-a", 8), group("h-m", 8));
        List<ErrorGroup> scannedTheOther = List.of(group("h-m", 8), group("h-z", 8), group("h-a", 8));

        List<String> first = hashesOf(service.decorate(dashboard(scannedOneWay)).errorGroups());
        List<String> second =
                hashesOf(service.decorate(dashboard(scannedTheOther)).errorGroups());

        // R-SEM-23 deterministic total order: identical inputs in any scan order, one answer.
        assertThat(first).containsExactly("h-a", "h-m", "h-z").isEqualTo(second);
    }

    /* ---------------- ordering only: never hides, never touches ack state ---------------- */

    @Test
    void everyCardSurvivesTheReorderIncludingAcknowledgedOnesWithTheirOverlayIntact() {
        emptyLedger();
        ErrorGroupAcknowledgement ack =
                new ErrorGroupAcknowledgement("op1", NOW.toString(), "known noise", null, null, 300L, false, null, 0L);
        ErrorGroup acknowledged = group("h-a", 300).withAcknowledgement(ack);

        List<ErrorGroup> ordered = service(true)
                .decorate(dashboard(acknowledged, group("h-b", 21), group("h-c", 8)))
                .errorGroups();

        assertThat(hashesOf(ordered)).containsExactlyInAnyOrder("h-a", "h-b", "h-c");
        assertThat(ordered.stream()
                        .filter(g -> g.signatureHash().equals("h-a"))
                        .findFirst()
                        .orElseThrow()
                        .acknowledgement())
                .isSameAs(ack);
    }

    @Test
    void aFailingLedgerDegradesToTodaysOrderingInsteadOfBreakingTheLanding() {
        when(incidents.findByLastSeenGreaterThanEqual(any())).thenThrow(new IllegalStateException("store down"));
        TriageDashboardResponse response = dashboard(group("h-a", 300), group("h-b", 21));

        TriageDashboardResponse decorated = service(true).decorate(response);

        assertThat(decorated).isSameAs(response);
    }

    @Test
    void theModelIsBuiltOncePerTtlNotOncePerCardAndNotOncePerRequest() {
        // Six cards across two renders must cost ONE ledger pass — the score is a shared model,
        // never an N+1 over the aggregation's output (the do-no-harm posture, ARCH §2.2).
        emptyLedger();
        AttentionScoreService service = service(true);

        service.decorate(dashboard(group("h-a", 3), group("h-b", 2), group("h-c", 1)));
        service.decorate(dashboard(group("h-a", 3), group("h-b", 2), group("h-c", 1)));

        verify(incidents).findByLastSeenGreaterThanEqual(any());
        // An empty ledger short-circuits before the two aggregates even run: no rows to join onto.
        verify(occurrences, never()).arrivalsSince(any(), any(), any());
        verify(episodes, never()).closedEpisodeDurationSeconds();
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

    private void emptyLedger() {
        when(incidents.findByLastSeenGreaterThanEqual(any())).thenReturn(List.of());
        when(episodes.closedEpisodeDurationSeconds()).thenReturn(List.of());
        when(occurrences.arrivalsSince(any(), any(), any())).thenReturn(List.of());
    }

    /** Today's rule verbatim: {@code TriageAggregationService} sorts {@code total DESC}, stably. */
    private static List<ErrorGroup> countOnlyOrder(List<ErrorGroup> groups) {
        return groups.stream()
                .sorted(Comparator.comparingLong(ErrorGroup::total).reversed())
                .toList();
    }

    private static List<ErrorGroup> randomGroups(Random random, int size) {
        List<ErrorGroup> groups = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            groups.add(group("h-" + i, random.nextInt(500)));
        }
        return groups;
    }

    private static List<ErrorGroup> distinctTotalGroups(Random random, int size) {
        List<ErrorGroup> groups = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            groups.add(group("h-" + random.nextInt(1_000_000) + "-" + i, (long) i * 7 + 1));
        }
        return groups;
    }

    private static ErrorGroup group(String hash, long total) {
        return new ErrorGroup(hash, 2, "X", "msg", "raw", total, total, 0L, Map.of("engine-a", Map.of("k:v1", total)));
    }

    private static TriageDashboardResponse dashboard(ErrorGroup... groups) {
        return dashboard(List.of(groups));
    }

    private static TriageDashboardResponse dashboard(List<ErrorGroup> groups) {
        return new TriageDashboardResponse(NOW.toString(), List.of(), Map.of(), Map.of(), groups, Map.of());
    }

    private static List<String> hashesOf(List<ErrorGroup> groups) {
        return groups.stream().map(ErrorGroup::signatureHash).toList();
    }
}
