package io.inspector.heat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.client.ActivityInstancePage;
import io.inspector.client.ActivityInstanceRecord;
import io.inspector.dto.DefinitionHeat;
import io.inspector.dto.DefinitionHeat.Coverage;
import io.inspector.dto.DefinitionHeat.NodeHeat;
import io.inspector.dto.DefinitionHeat.Tier;
import io.inspector.heat.HeatAggregator.Instance;
import io.inspector.heat.HeatAggregator.Sample;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Rung 1, pure (DEFINITION-HEATMAP.md §8). Rows come from the recorded 6.8 / 7.1 fixtures where one
 * exists; no 6.3 activity fixture is recorded, so 6.3-shaped, loop, multi-visit and funnel cases are
 * built with the typed record constructor (same shape the wire mapper produces).
 *
 * <p>Pinned decisions: hit rate = distinct instances that visited the node at least once over the
 * analysed (sampled minus dropped) instances; {@code executions} counts every visit; the average
 * is over all TIMED visits; an unfinished row counts as a visit but is untimed; percentile =
 * nearest rank {@code value[ceil(p/100*n)-1]} over sorted timed durations; p95 needs n >= 20.
 */
class HeatAggregatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant ASOF = Instant.parse("2026-11-18T10:00:00Z");

    private static ActivityInstanceRecord row(String pi, String act, String type, Long dur) {
        return row(pi, act, type, dur, "2026-11-17T10:00:00.000Z");
    }

    private static ActivityInstanceRecord row(String pi, String act, String type, Long dur, String start) {
        return new ActivityInstanceRecord(
                pi + "-" + act + "-" + start,
                act,
                act.toUpperCase(),
                type,
                "x-" + pi,
                pi,
                "def:1",
                null,
                null,
                null,
                start,
                dur == null ? null : "2026-11-17T10:00:01.000Z",
                dur,
                "");
    }

    private static Instance inst(ActivityInstanceRecord... rows) {
        return new Instance(List.of(rows), rows.length, false);
    }

    private static DefinitionHeat agg(List<Instance> instances) {
        return agg(instances, instances.size());
    }

    private static DefinitionHeat agg(List<Instance> instances, long finishedTotal) {
        return HeatAggregator.aggregate(new Sample("e1", "def:1", ASOF, 200, finishedTotal, null, null, instances));
    }

    private static NodeHeat node(DefinitionHeat h, String id) {
        var found = h.nodes().stream().filter(n -> n.activityId().equals(id)).findFirst();
        assertThat(found).as("node " + id).isPresent();
        return found.get();
    }

    private static ActivityInstancePage fixture(String version, String name) throws IOException {
        try (InputStream in = HeatAggregatorTest.class.getResourceAsStream("/wire/" + version + "/" + name)) {
            return MAPPER.readValue(in, ActivityInstancePage.class);
        }
    }

    private static List<Instance> timedInstances(String node, long... durations) {
        List<Instance> out = new ArrayList<>();
        for (int i = 0; i < durations.length; i++) {
            out.add(inst(row("pi" + i, node, "userTask", durations[i])));
        }
        return out;
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void recordedFixtureCountsNodeAndIgnoresFlowRow(String version) throws IOException {
        ActivityInstancePage page = fixture(version, "historic-activity-instances.json");
        DefinitionHeat h = agg(List.of(new Instance(page.data(), page.total(), false)));

        assertThat(h.coverage()).isEqualTo(Coverage.FULL);
        assertThat(h.nodes()).extracting(NodeHeat::activityId).containsExactly("start");
        assertThat(node(h, "start").executions()).isEqualTo(1);
        assertThat(node(h, "start").hitPct()).isEqualTo(100.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void recordedUnfinishedRowsCountAsVisitsButAreUntimed(String version) throws IOException {
        ActivityInstancePage page = fixture(version, "historic-activity-instances-unfinished.json");
        DefinitionHeat h = agg(List.of(new Instance(page.data(), page.total(), false)));

        NodeHeat n = node(h, "waitForMessage");
        assertThat(n.executions()).isEqualTo(1);
        assertThat(n.instances()).isEqualTo(1);
        assertThat(n.avgMs()).isNull();
        assertThat(n.p50Ms()).isNull();
        assertThat(n.p95Ms()).isNull();
        assertThat(n.maxMs()).isNull();
        assertThat(n.totalMs()).isZero();
        assertThat(n.tier()).isEqualTo(Tier.NONE);
    }

    @Test
    void sixThreeShapedRowsWithoutAnyFlowRowGiveNoFlowHistory() {
        DefinitionHeat h = agg(List.of(inst(row("a", "t", "userTask", 5L)), inst(row("b", "t", "userTask", 7L))));

        assertThat(h.coverage()).isEqualTo(Coverage.NO_FLOW_HISTORY);
        assertThat(node(h, "t").executions()).isEqualTo(2);
    }

    @Test
    void oneFlowRowAnywhereKeepsFullCoverage() {
        DefinitionHeat h = agg(List.of(
                inst(row("a", "t", "userTask", 5L)),
                inst(row("b", "t", "userTask", 7L), row("b", "f1", "sequenceFlow", 0L))));

        assertThat(h.coverage()).isEqualTo(Coverage.FULL);
        assertThat(h.nodes()).extracting(NodeHeat::activityId).containsExactly("t");
    }

    @Test
    void instanceWithZeroRowsGivesNoActivityHistory() {
        DefinitionHeat h = agg(List.of(new Instance(List.of(), 0, false)));

        assertThat(h.coverage()).isEqualTo(Coverage.NO_ACTIVITY_HISTORY);
        assertThat(h.nodes()).isEmpty();
    }

    @Test
    void emptySampleGivesNoHistory() {
        DefinitionHeat h = agg(List.of(), 0);

        assertThat(h.coverage()).isEqualTo(Coverage.NO_HISTORY);
        assertThat(h.nodes()).isEmpty();
        assertThat(h.sampledInstances()).isZero();
    }

    @Test
    void overLongInstanceIsDroppedAndNeverHalfCounted() {
        Instance overLong = new Instance(List.of(row("long", "t", "userTask", 999L)), 400, false);
        DefinitionHeat h = agg(List.of(inst(row("a", "t", "userTask", 10L)), overLong), 50);

        assertThat(h.sampledInstances()).isEqualTo(2);
        assertThat(h.droppedInstances()).isEqualTo(1);
        assertThat(h.dropReasons().tooManyRows()).isEqualTo(1);
        assertThat(h.dropReasons().readFailed()).isZero();
        assertThat(h.partial()).isTrue();
        assertThat(node(h, "t").executions()).isEqualTo(1);
        assertThat(node(h, "t").totalMs()).isEqualTo(10L);
        assertThat(node(h, "t").hitPct()).isEqualTo(100.0);
        assertThat(h.finishedTotal()).isEqualTo(50);
    }

    @Test
    void failedReadIsDroppedWithItsOwnReason() {
        Instance failed = new Instance(List.of(), 0, true);
        DefinitionHeat h = agg(List.of(inst(row("a", "t", "userTask", 10L)), failed));

        assertThat(h.droppedInstances()).isEqualTo(1);
        assertThat(h.dropReasons().readFailed()).isEqualTo(1);
        assertThat(h.dropReasons().tooManyRows()).isZero();
        assertThat(h.partial()).isTrue();
        assertThat(h.coverage()).isNotEqualTo(Coverage.NO_ACTIVITY_HISTORY);
    }

    @Test
    void noDropMeansNotPartial() {
        assertThat(agg(List.of(inst(row("a", "t", "userTask", 1L)))).partial()).isFalse();
    }

    @Test
    void everyInstanceDroppedIsAnErrorNotAnEmptyMap() {
        List<Instance> all = List.of(new Instance(List.of(), 0, true), new Instance(List.of(), 0, true));

        assertThatThrownBy(() -> agg(all)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hitPctIsOverAnalysedInstancesNotSampled() {
        DefinitionHeat h = agg(List.of(
                inst(row("a", "t", "userTask", 1L)),
                inst(row("b", "u", "userTask", 1L)),
                new Instance(List.of(), 0, true)));

        assertThat(node(h, "t").hitPct()).isEqualTo(50.0);
    }

    @Test
    void parallelJoinVisitedTwiceInOneInstanceIsTwoExecutionsOneInstance() {
        DefinitionHeat h = agg(List.of(
                inst(row("a", "join", "parallelGateway", 0L), row("a", "join", "parallelGateway", 0L)),
                inst(row("b", "other", "userTask", 3L))));

        NodeHeat j = node(h, "join");
        assertThat(j.executions()).isEqualTo(2);
        assertThat(j.instances()).isEqualTo(1);
        assertThat(j.hitPct()).isEqualTo(50.0);
    }

    @Test
    void loopAverageIsOverAllVisitsNotPerInstance() {
        DefinitionHeat h = agg(List.of(
                inst(row("a", "t", "userTask", 10L), row("a", "t", "userTask", 20L), row("a", "t", "userTask", 60L)),
                inst(row("b", "t", "userTask", 30L))));

        NodeHeat t = node(h, "t");
        assertThat(t.executions()).isEqualTo(4);
        assertThat(t.instances()).isEqualTo(2);
        assertThat(t.avgMs()).isEqualTo(30.0);
        assertThat(t.maxMs()).isEqualTo(60L);
        assertThat(t.totalMs()).isEqualTo(120L);
    }

    @Test
    void unfinishedRowInFinishedInstanceCountsAsVisitButNotInTiming() {
        DefinitionHeat h = agg(List.of(
                inst(row("a", "t", "userTask", 10L), row("a", "t", "userTask", null)),
                inst(row("b", "t", "userTask", 30L))));

        NodeHeat t = node(h, "t");
        assertThat(t.executions()).isEqualTo(3);
        assertThat(t.instances()).isEqualTo(2);
        assertThat(t.avgMs()).isEqualTo(20.0);
        assertThat(t.totalMs()).isEqualTo(40L);
    }

    @Test
    void singleInstance() {
        NodeHeat t = node(agg(List.of(inst(row("a", "t", "userTask", 42L)))), "t");

        assertThat(t.hitPct()).isEqualTo(100.0);
        assertThat(t.avgMs()).isEqualTo(42.0);
        assertThat(t.p50Ms()).isEqualTo(42L);
        assertThat(t.p95Ms()).isNull();
        assertThat(t.sharePct()).isEqualTo(100.0);
    }

    @Test
    void nearestRankWorkedExampleOneToTwenty() {
        long[] d = IntStream.rangeClosed(1, 20).asLongStream().toArray();
        NodeHeat t = node(agg(timedInstances("t", d)), "t");

        assertThat(t.p50Ms()).isEqualTo(10L); // ceil(.50*20)=10 -> value 10
        assertThat(t.p95Ms()).isEqualTo(19L); // ceil(.95*20)=19 -> value 19
        assertThat(t.maxMs()).isEqualTo(20L);
    }

    @Test
    void nearestRankWorkedExampleOneToTwentyOneUnsorted() {
        long[] d = IntStream.rangeClosed(1, 21).map(i -> 22 - i).asLongStream().toArray();
        NodeHeat t = node(agg(timedInstances("t", d)), "t");

        assertThat(t.p50Ms()).isEqualTo(11L); // ceil(10.5)=11
        assertThat(t.p95Ms()).isEqualTo(20L); // ceil(19.95)=20
    }

    @Test
    void p95FloorBoundary() {
        long[] n19 = IntStream.rangeClosed(1, 19).asLongStream().toArray();
        long[] n20 = IntStream.rangeClosed(1, 20).asLongStream().toArray();
        long[] n21 = IntStream.rangeClosed(1, 21).asLongStream().toArray();

        assertThat(node(agg(timedInstances("t", n19)), "t").p95Ms()).isNull();
        assertThat(node(agg(timedInstances("t", n19)), "t").p50Ms()).isEqualTo(10L);
        assertThat(node(agg(timedInstances("t", n20)), "t").p95Ms()).isNotNull();
        assertThat(node(agg(timedInstances("t", n21)), "t").p95Ms()).isNotNull();
    }

    @Test
    void floorCountsTimedExecutionsNotUnfinishedOnes() {
        List<Instance> in = new ArrayList<>(
                timedInstances("t", IntStream.rangeClosed(1, 19).asLongStream().toArray()));
        in.add(inst(row("u", "t", "userTask", null)));

        assertThat(node(agg(in), "t").p95Ms()).isNull();
    }

    @Test
    void equalDurations() {
        long[] d = new long[25];
        java.util.Arrays.fill(d, 5L);
        NodeHeat t = node(agg(timedInstances("t", d)), "t");

        assertThat(t.p50Ms()).isEqualTo(5L);
        assertThat(t.p95Ms()).isEqualTo(5L);
        assertThat(t.maxMs()).isEqualTo(5L);
        assertThat(t.avgMs()).isEqualTo(5.0);
    }

    @Test
    void shareAndTierThresholds() {
        DefinitionHeat h = agg(List.of(inst(
                row("a", "A", "userTask", 500L),
                row("a", "B", "userTask", 400L),
                row("a", "C", "userTask", 90L),
                row("a", "D", "userTask", 10L),
                row("a", "E", "serviceTask", 0L))));

        assertThat(node(h, "A").sharePct()).isEqualTo(50.0);
        assertThat(node(h, "A").tier()).isEqualTo(Tier.DOMINANT);
        assertThat(node(h, "B").tier()).isEqualTo(Tier.DOMINANT); // 40 % boundary inclusive
        assertThat(node(h, "C").tier()).isEqualTo(Tier.COOL); // 9 %
        assertThat(node(h, "D").tier()).isEqualTo(Tier.COOL); // 1 % boundary inclusive
        assertThat(node(h, "E").tier()).isEqualTo(Tier.NONE); // sub-1 ms, 0 %
        assertThat(h.nodes()).extracting(NodeHeat::activityId).containsExactly("A", "B", "C", "D", "E");
    }

    @Test
    void warmTierAtTenPercent() {
        DefinitionHeat h = agg(List.of(inst(row("a", "A", "userTask", 90L), row("a", "B", "userTask", 10L))));

        assertThat(node(h, "B").tier()).isEqualTo(Tier.WARM);
        assertThat(node(h, "A").tier()).isEqualTo(Tier.DOMINANT);
    }

    @Test
    void zeroTotalTimeGivesZeroShareNotNaN() {
        DefinitionHeat h = agg(List.of(inst(row("a", "g", "exclusiveGateway", 0L))));

        assertThat(node(h, "g").sharePct()).isEqualTo(0.0);
        assertThat(node(h, "g").tier()).isEqualTo(Tier.NONE);
    }

    @Test
    void funnelPlusNoEndEventAddsUpToAnalysedSample() {
        DefinitionHeat h = agg(List.of(
                inst(row("a", "ok", "endEvent", 0L)),
                inst(row("b", "ok", "endEvent", 0L)),
                inst(row("c", "no", "endEvent", 0L)),
                inst(row("d", "t", "userTask", 5L)),
                new Instance(List.of(), 0, true)));

        assertThat(h.outcomes()).extracting(DefinitionHeat.Outcome::endEventId).containsExactly("ok", "no");
        assertThat(h.outcomes().get(0).count()).isEqualTo(2);
        assertThat(h.outcomes().get(0).pct()).isEqualTo(50.0);
        assertThat(h.outcomes().get(0).name()).isEqualTo("OK");
        assertThat(h.noEndEventCount()).isEqualTo(1);
        assertThat(h.outcomes().stream().mapToInt(DefinitionHeat.Outcome::count).sum() + h.noEndEventCount())
                .isEqualTo(h.sampledInstances() - h.droppedInstances());
    }

    @Test
    void instanceWithTwoEndEventsCountsOnceUnderTheLastStarted() {
        DefinitionHeat h = agg(List.of(inst(
                row("a", "early", "endEvent", 0L, "2026-11-17T10:00:00.000Z"),
                row("a", "late", "endEvent", 0L, "2026-11-17T10:00:05.000Z"))));

        assertThat(h.outcomes()).extracting(DefinitionHeat.Outcome::endEventId).containsExactly("late");
        assertThat(h.noEndEventCount()).isZero();
    }

    @Test
    void errorEndEventTypeAlsoCountsAsOutcome() {
        DefinitionHeat h = agg(List.of(inst(row("a", "boom", "errorEndEvent", 0L))));

        assertThat(h.outcomes()).extracting(DefinitionHeat.Outcome::endEventId).containsExactly("boom");
    }

    @Test
    void honestyFieldsAreCarriedThrough() {
        Instant old = Instant.parse("2026-11-10T00:00:00Z");
        Instant now = Instant.parse("2026-11-17T00:00:00Z");
        DefinitionHeat h = HeatAggregator.aggregate(
                new Sample("e1", "def:1", ASOF, 200, 10_000, old, now, List.of(inst(row("a", "t", "userTask", 1L)))));

        assertThat(h.engineId()).isEqualTo("e1");
        assertThat(h.definitionId()).isEqualTo("def:1");
        assertThat(h.asOf()).isEqualTo(ASOF);
        assertThat(h.requestedN()).isEqualTo(200);
        assertThat(h.finishedTotal()).isEqualTo(10_000);
        assertThat(h.sampledInstances()).isEqualTo(1);
        assertThat(h.oldestEnd()).isEqualTo(old);
        assertThat(h.newestEnd()).isEqualTo(now);
        assertThat(h.cached()).isFalse();
        assertThat(h.samplingNote()).isEqualTo("Newest 1 of 10000 finished instances (sample, not the full history).");
    }
}
