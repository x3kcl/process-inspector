package io.inspector.heat;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.client.ActivityInstanceRecord;
import io.inspector.dto.DefinitionHeat;
import io.inspector.heat.HeatAggregator.Instance;
import io.inspector.heat.HeatAggregator.Sample;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The sampling note must not call a complete read a sample: when every finished instance was
 * read, "sample, not the full history" is wrong (seen on the demo: "Newest 4 of 4 finished
 * instances (sample, not the full history)").
 */
class HeatAggregatorSamplingNoteTest {

    private static final Instant ASOF = Instant.parse("2026-11-18T10:00:00Z");

    private static Instance inst(int i) {
        ActivityInstanceRecord row = new ActivityInstanceRecord(
                "pi" + i + "-t",
                "t",
                "T",
                "userTask",
                "x-pi" + i,
                "pi" + i,
                "def:1",
                null,
                null,
                null,
                "2026-11-17T10:00:00.000Z",
                "2026-11-17T10:00:01.000Z",
                1000L,
                "");
        return new Instance(List.of(row), 1, false);
    }

    private static DefinitionHeat agg(int sampled, long finishedTotal) {
        List<Instance> instances = IntStream.range(0, sampled)
                .mapToObj(HeatAggregatorSamplingNoteTest::inst)
                .toList();
        return HeatAggregator.aggregate(new Sample("e1", "def:1", ASOF, 200, finishedTotal, ASOF, ASOF, instances));
    }

    @Test
    void aSampleSmallerThanTheHistorySaysItIsASample() {
        assertThat(agg(3, 4).samplingNote())
                .isEqualTo("Newest 3 of 4 finished instances (sample, not the full history).");
    }

    @Test
    void readingEveryFinishedInstanceIsNotCalledASample() {
        assertThat(agg(4, 4).samplingNote()).isEqualTo("All 4 finished instances.");
    }

    @Test
    void aSingleFinishedInstanceReadInFullIsSingular() {
        assertThat(agg(1, 1).samplingNote()).isEqualTo("The 1 finished instance.");
    }

    @Test
    void droppedInstancesAreStillNamedWhenAllWereRead() {
        List<Instance> instances = List.of(inst(0), inst(1), new Instance(List.of(), 99, false));
        DefinitionHeat h = HeatAggregator.aggregate(new Sample("e1", "def:1", ASOF, 200, 3, ASOF, ASOF, instances));

        assertThat(h.samplingNote()).isEqualTo("All 3 finished instances; 1 dropped, figures over the remaining 2.");
    }
}
