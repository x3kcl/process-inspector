package io.inspector.heat;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.client.ActivityInstanceRecord;
import io.inspector.dto.DefinitionHeat;
import io.inspector.dto.DefinitionHeat.NodeHeat;
import io.inspector.heat.HeatAggregator.Instance;
import io.inspector.heat.HeatAggregator.Sample;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * With route history the outgoing flow row IS the record (#783, decision A); timing only picks
 * which visit it belongs to. A flow row stamped a millisecond before its catch event's end still
 * makes the event taken.
 */
class HeatAggregatorEventGatewayRouteRecordTest {

    private static ActivityInstanceRecord row(String act, String type, long start, long end) {
        Instant t0 = Instant.parse("2026-11-17T10:00:00Z");
        return new ActivityInstanceRecord(
                act + start,
                act,
                act,
                type,
                "x",
                "a",
                "def:1",
                null,
                null,
                null,
                t0.plusMillis(start).toString(),
                t0.plusMillis(end).toString(),
                end - start,
                "");
    }

    @Test
    void flowRowStampedBeforeTheEventEndStillMakesItTaken() throws IOException {
        String xml;
        try (InputStream in = getClass().getResourceAsStream("/heat/heatEvgw.bpmn20.xml")) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<ActivityInstanceRecord> rows = List.of(
                row("catchA", "intermediateCatchEvent", 0, 10),
                row("catchB", "intermediateCatchEvent", 0, 10),
                row("fa2", "sequenceFlow", 9, 9),
                row("endA", "endEvent", 10, 10));
        DefinitionHeat h = HeatAggregator.aggregate(new Sample(
                "e1", "def:1", Instant.EPOCH, 200, 1, null, null, List.of(new Instance(rows, 4, false)), xml));

        NodeHeat a = h.nodes().stream()
                .filter(n -> n.activityId().equals("catchA"))
                .findFirst()
                .orElseThrow();
        assertThat(a.executions()).isEqualTo(1);
        assertThat(a.armedNotTaken()).isZero();
    }
}
