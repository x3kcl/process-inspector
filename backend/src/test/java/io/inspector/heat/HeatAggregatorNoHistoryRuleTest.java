package io.inspector.heat;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.client.ActivityInstanceRecord;
import io.inspector.dto.DefinitionHeat;
import io.inspector.dto.DefinitionHeat.Coverage;
import io.inspector.dto.DefinitionHeat.DropReasons;
import io.inspector.dto.DefinitionHeat.NodeHeat;
import io.inspector.heat.HeatAggregator.Instance;
import io.inspector.heat.HeatAggregator.Sample;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Rung 1, pure — owner decision B (2026-11-18, #783): {@code NO_ACTIVITY_HISTORY} only when EVERY
 * analysed instance (not dropped for another reason) has zero activity rows. Otherwise an empty
 * instance is dropped like any unreadable one, with its own reason ({@code noActivityHistory}),
 * and the sampling note says how many were dropped and what the figures are over.
 */
class HeatAggregatorNoHistoryRuleTest {

    private static ActivityInstanceRecord row(String pi, String act, String type) {
        return new ActivityInstanceRecord(
                pi + act,
                act,
                act,
                type,
                "x",
                pi,
                "def:1",
                null,
                null,
                null,
                "2026-11-17T10:00:00.000Z",
                "2026-11-17T10:00:01.000Z",
                1000L,
                "");
    }

    private static Instance full(String pi) {
        List<ActivityInstanceRecord> r = List.of(row(pi, "t", "userTask"), row(pi, "end", "endEvent"));
        return new Instance(r, r.size(), false);
    }

    private static final Instance EMPTY = new Instance(List.of(), 0, false);
    private static final Instance READ_FAILED = new Instance(List.of(), 0, true);

    private static DefinitionHeat agg(List<Instance> in) {
        return HeatAggregator.aggregate(new Sample("e1", "def:1", Instant.EPOCH, 200, in.size(), null, null, in));
    }

    private static List<Instance> sample(int n, int empty) {
        List<Instance> in = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            in.add(i < empty ? EMPTY : full("pi" + i));
        }
        return in;
    }

    private static NodeHeat node(DefinitionHeat h, String id) {
        return h.nodes().stream()
                .filter(n -> n.activityId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no node " + id + " in " + h.nodes()));
    }

    @ParameterizedTest(name = "{1} of {0} empty")
    @CsvSource({"4, 0", "4, 1", "4, 3", "1, 0"})
    void someButNotAllEmptyDropsTheEmptyOnesAndKeepsTheFigures(int n, int empty) {
        DefinitionHeat h = agg(sample(n, empty));

        assertThat(h.coverage()).isEqualTo(Coverage.NO_FLOW_HISTORY);
        assertThat(h.sampledInstances()).isEqualTo(n);
        assertThat(h.droppedInstances()).isEqualTo(empty);
        assertThat(h.dropReasons()).isEqualTo(new DropReasons(0, 0, empty));
        assertThat(h.partial()).isEqualTo(empty > 0);
        assertThat(node(h, "t").instances()).isEqualTo(n - empty);
        assertThat(node(h, "t").hitPct()).isEqualTo(100.0);
        assertThat(h.outcomes().get(0).count() + h.noEndEventCount()).isEqualTo(n - empty);
        assertThat(h.samplingNote())
                .isEqualTo("Newest %d of %d finished instances (sample, not the full history)%s."
                        .formatted(
                                n,
                                n,
                                empty > 0
                                        ? "; %d dropped, figures over the remaining %d".formatted(empty, n - empty)
                                        : ""));
    }

    @ParameterizedTest(name = "{0} of {0} empty")
    @CsvSource({"4", "1"})
    void allEmptyIsNoActivityHistoryNotADrop(int n) {
        DefinitionHeat h = agg(sample(n, n));

        assertThat(h.coverage()).isEqualTo(Coverage.NO_ACTIVITY_HISTORY);
        assertThat(h.nodes()).isEmpty();
        assertThat(h.outcomes()).isEmpty();
        assertThat(h.droppedInstances()).isZero();
        assertThat(h.dropReasons()).isEqualTo(new DropReasons(0, 0, 0));
        assertThat(h.partial()).isFalse();
    }

    @Test
    void theThreeDropReasonsAreCountedApart() {
        Instance overLong = new Instance(List.of(row("long", "t", "userTask")), 500, false);
        DefinitionHeat h = agg(List.of(full("a"), EMPTY, READ_FAILED, overLong));

        assertThat(h.coverage()).isEqualTo(Coverage.NO_FLOW_HISTORY);
        assertThat(h.droppedInstances()).isEqualTo(3);
        assertThat(h.dropReasons()).isEqualTo(new DropReasons(1, 1, 1));
        assertThat(h.samplingNote()).endsWith("; 3 dropped, figures over the remaining 1.");
    }

    @Test
    void emptyPlusFailedReadsOnlyIsStillNoActivityHistoryWithTheFailedReadsDropped() {
        DefinitionHeat h = agg(List.of(EMPTY, READ_FAILED));

        assertThat(h.coverage()).isEqualTo(Coverage.NO_ACTIVITY_HISTORY);
        assertThat(h.droppedInstances()).isEqualTo(1);
        assertThat(h.dropReasons()).isEqualTo(new DropReasons(0, 1, 0));
        assertThat(h.partial()).isTrue();
    }
}
