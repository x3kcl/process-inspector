package io.inspector.heat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.client.ActivityInstancePage;
import io.inspector.client.ActivityInstanceRecord;
import io.inspector.dto.DefinitionHeat;
import io.inspector.dto.DefinitionHeat.Coverage;
import io.inspector.dto.DefinitionHeat.NodeHeat;
import io.inspector.dto.DefinitionHeat.Outcome;
import io.inspector.heat.HeatAggregator.Instance;
import io.inspector.heat.HeatAggregator.Sample;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Rung 1 over RECORDED rows (DEFINITION-HEATMAP.md §1.1, §8). The fixtures were recorded on fresh
 * throwaway flowable-rest 6.3.1 / 6.8.0 / 7.1.0 engines: one Escalated instance of the §1.1 model
 * (route → check → split → c1 ‖ c2 → join → endEscalated), and one instance of an event-based
 * gateway with two message catch events where only {@code catchA} fired.
 */
class HeatAggregatorRecordedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static DefinitionHeat recorded(String version, String name) throws IOException {
        try (InputStream in = HeatAggregatorRecordedTest.class.getResourceAsStream(
                "/wire/" + version + "/historic-activity-instances-" + name + ".json")) {
            ActivityInstancePage p = MAPPER.readValue(in, ActivityInstancePage.class);
            return HeatAggregator.aggregate(new Sample(
                    "e1",
                    "def:1",
                    Instant.EPOCH,
                    200,
                    1,
                    null,
                    null,
                    List.of(new Instance(p.data(), p.total(), false))));
        }
    }

    private static NodeHeat node(DefinitionHeat h, String id) {
        return h.nodes().stream()
                .filter(n -> n.activityId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.3", "6.8", "7.1"})
    void escalatedInstanceGivesEightNodesJoinTwiceAndOneOutcome(String version) throws IOException {
        DefinitionHeat h = recorded(version, "escalated");

        assertThat(h.coverage()).isEqualTo(version.equals("6.3") ? Coverage.NO_FLOW_HISTORY : Coverage.FULL);
        assertThat(h.nodes())
                .extracting(NodeHeat::activityId)
                .containsExactlyInAnyOrder("start", "route", "check", "split", "c1", "c2", "join", "endEscalated");
        assertThat(node(h, "join").executions()).isEqualTo(2);
        assertThat(node(h, "join").instances()).isEqualTo(1);
        assertThat(node(h, "join").hitPct()).isEqualTo(100.0);
        assertThat(h.outcomes()).containsExactly(new Outcome("endEscalated", "Escalated", 1, 100.0));
        assertThat(h.noEndEventCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.3", "6.8", "7.1"})
    void eventGatewayInstanceCountsOnlyTheFiredBranchInTheFunnel(String version) throws IOException {
        DefinitionHeat h = recorded(version, "evgw");

        assertThat(h.coverage()).isEqualTo(version.equals("6.3") ? Coverage.NO_FLOW_HISTORY : Coverage.FULL);
        assertThat(h.nodes())
                .extracting(NodeHeat::activityId)
                .containsExactlyInAnyOrder("start", "gw", "catchA", "catchB", "endA");
        assertThat(h.outcomes()).extracting(Outcome::endEventId).containsExactly("endA");
        assertThat(h.noEndEventCount()).isZero();
    }

    @Test
    void samplingNoteNamesTheDroppedInstancesAndWhatTheFiguresAreOver() {
        ActivityInstanceRecord r = new ActivityInstanceRecord(
                "r",
                "t",
                "T",
                "userTask",
                "x",
                "a",
                "def:1",
                null,
                null,
                null,
                "2026-11-17T10:00:00.000Z",
                "2026-11-17T10:00:01.000Z",
                1L,
                "");
        DefinitionHeat h = HeatAggregator.aggregate(new Sample(
                "e1",
                "def:1",
                Instant.EPOCH,
                200,
                50,
                null,
                null,
                List.of(new Instance(List.of(r), 1, false), new Instance(List.of(), 0, true))));

        assertThat(h.samplingNote())
                .isEqualTo("Newest 2 of 50 finished instances (sample, not the full history);"
                        + " 1 dropped, figures over the remaining 1.");
    }
}
