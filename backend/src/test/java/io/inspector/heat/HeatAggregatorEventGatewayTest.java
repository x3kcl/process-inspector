package io.inspector.heat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.client.ActivityInstancePage;
import io.inspector.client.ActivityInstanceRecord;
import io.inspector.dto.DefinitionHeat;
import io.inspector.dto.DefinitionHeat.Coverage;
import io.inspector.dto.DefinitionHeat.NodeHeat;
import io.inspector.dto.DefinitionHeat.Structure;
import io.inspector.heat.HeatAggregator.Instance;
import io.inspector.heat.HeatAggregator.Sample;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Rung 1, pure — owner decision A (2026-11-18, #783): a catch event that an event-based gateway
 * ARMED but that did not fire is left out of hit rate, visit count, duration statistics and the
 * time-share denominator, and counted in {@code armedNotTaken}. The recorded {@code evgw} rows
 * (6.3.1 / 6.8.0 / 7.1.0) come from {@code /heat/heatEvgw.bpmn20.xml}: only {@code catchA} fired,
 * yet {@code catchB} has a finished row with a duration on every version.
 *
 * <p>Taken rule per instance: with route history (any {@code sequenceFlow} row in the instance),
 * an event row is taken iff one of its outgoing flows has a row; without (6.3), iff one of its
 * direct successors has a row starting at or after the event row's end — inferred, and the
 * sampling note says so. Each successor row takes at most one event row (the latest-ending one
 * that ended before it), so a loop counts each visit once.
 */
class HeatAggregatorEventGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE_NOTE = "The 1 finished instance";
    private static final String INFERRED =
            "; event-gateway branches inferred from timing, this engine records no routes";

    /** start → gw ⇒ catchA → endA, catchB → t → back to gw (fa/fb from gw, fa2/fb2/ft onward). */
    private static final String LOOP_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <process id="loop" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="f0" sourceRef="start" targetRef="gw"/>
                <eventBasedGateway id="gw"/>
                <sequenceFlow id="fa" sourceRef="gw" targetRef="catchA"/>
                <sequenceFlow id="fb" sourceRef="gw" targetRef="catchB"/>
                <intermediateCatchEvent id="catchA"><messageEventDefinition/></intermediateCatchEvent>
                <intermediateCatchEvent id="catchB"><messageEventDefinition/></intermediateCatchEvent>
                <sequenceFlow id="fa2" sourceRef="catchA" targetRef="endA"/>
                <sequenceFlow id="fb2" sourceRef="catchB" targetRef="t"/>
                <userTask id="t"/>
                <sequenceFlow id="ft" sourceRef="t" targetRef="gw"/>
                <endEvent id="endA"/>
              </process>
            </definitions>""";

    private static String recordedBpmn() throws IOException {
        try (InputStream in = HeatAggregatorEventGatewayTest.class.getResourceAsStream("/heat/heatEvgw.bpmn20.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Instance recordedRows(String version) throws IOException {
        try (InputStream in = HeatAggregatorEventGatewayTest.class.getResourceAsStream(
                "/wire/" + version + "/historic-activity-instances-evgw.json")) {
            ActivityInstancePage p = MAPPER.readValue(in, ActivityInstancePage.class);
            return new Instance(p.data(), p.total(), false);
        }
    }

    private static DefinitionHeat agg(String xml, Instance... instances) {
        return HeatAggregator.aggregate(
                new Sample("e1", "def:1", Instant.EPOCH, 200, instances.length, null, null, List.of(instances), xml));
    }

    private static NodeHeat node(DefinitionHeat h, String id) {
        return h.nodes().stream()
                .filter(n -> n.activityId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no node " + id + " in " + h.nodes()));
    }

    /** A row on a millisecond clock: start/end in ms after 10:00:00.000Z, end null = unfinished. */
    private static ActivityInstanceRecord row(String pi, String act, String type, long start, Long end) {
        return new ActivityInstanceRecord(
                pi + "-" + act + "-" + start,
                act,
                act,
                type,
                "x-" + pi,
                pi,
                "def:1",
                null,
                null,
                null,
                at(start),
                end == null ? null : at(end),
                end == null ? null : end - start,
                "");
    }

    private static String at(long ms) {
        return Instant.parse("2026-11-17T10:00:00Z").plusMillis(ms).toString();
    }

    private static Instance inst(List<ActivityInstanceRecord> rows) {
        return new Instance(rows, rows.size(), false);
    }

    /**
     * catchB fires in iteration 1 (0→10, taken), catchA in iteration 2 (20→50, taken); the other
     * catch event of each iteration is armed (catchA 0→10, catchB 20→49).
     */
    private static List<ActivityInstanceRecord> loopRows(boolean withFlows) {
        List<ActivityInstanceRecord> r = new ArrayList<>(List.of(
                row("a", "start", "startEvent", 0, 0L),
                row("a", "gw", "eventGateway", 0, 0L),
                row("a", "catchA", "intermediateCatchEvent", 0, 10L),
                row("a", "catchB", "intermediateCatchEvent", 0, 10L),
                row("a", "t", "userTask", 10, 20L),
                row("a", "gw", "eventGateway", 20, 20L),
                row("a", "catchA", "intermediateCatchEvent", 20, 50L),
                row("a", "catchB", "intermediateCatchEvent", 20, 49L),
                row("a", "endA", "endEvent", 50, 50L)));
        if (withFlows) {
            r.addAll(List.of(
                    row("a", "f0", "sequenceFlow", 0, 0L),
                    row("a", "fa", "sequenceFlow", 0, 0L),
                    row("a", "fb", "sequenceFlow", 0, 0L),
                    row("a", "fb2", "sequenceFlow", 10, 10L),
                    row("a", "ft", "sequenceFlow", 20, 20L),
                    row("a", "fa", "sequenceFlow", 20, 20L),
                    row("a", "fb", "sequenceFlow", 20, 20L),
                    row("a", "fa2", "sequenceFlow", 50, 50L)));
        }
        return r;
    }

    @ParameterizedTest
    @CsvSource({"6.3, 97, 98", "6.8, 121, 123", "7.1, 103, 104"})
    void recordedArmedCatchEventIsLeftOutOfHitTimingAndShare(String version, long catchAMs, long grandMs)
            throws IOException {
        DefinitionHeat h = agg(recordedBpmn(), recordedRows(version));

        assertThat(h.structure()).isEqualTo(Structure.RESOLVED);
        assertThat(h.coverage()).isEqualTo(version.equals("6.3") ? Coverage.NO_FLOW_HISTORY : Coverage.FULL);

        NodeHeat b = node(h, "catchB");
        assertThat(b.executions()).isZero();
        assertThat(b.instances()).isZero();
        assertThat(b.hitPct()).isEqualTo(0.0);
        assertThat(b.armedNotTaken()).isEqualTo(1);
        assertThat(b.avgMs()).isNull();
        assertThat(b.p50Ms()).isNull();
        assertThat(b.maxMs()).isNull();
        assertThat(b.totalMs()).isZero();
        assertThat(b.sharePct()).isEqualTo(0.0);

        NodeHeat a = node(h, "catchA");
        assertThat(a.executions()).isEqualTo(1);
        assertThat(a.hitPct()).isEqualTo(100.0);
        assertThat(a.armedNotTaken()).isZero();
        assertThat(a.totalMs()).isEqualTo(catchAMs);
        assertThat(a.sharePct()).isCloseTo(catchAMs * 100.0 / grandMs, within(1e-9));
        assertThat(h.nodes().stream().mapToLong(NodeHeat::totalMs).sum()).isEqualTo(grandMs);
        assertThat(h.nodes().stream().mapToDouble(NodeHeat::sharePct).sum()).isCloseTo(100.0, within(1e-9));
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.3", "6.8", "7.1"})
    void onlyTheEngineWithoutRouteHistorySaysTheSplitIsInferred(String version) throws IOException {
        DefinitionHeat h = agg(recordedBpmn(), recordedRows(version));

        assertThat(h.samplingNote()).isEqualTo(BASE_NOTE + (version.equals("6.3") ? INFERRED : "") + ".");
    }

    @Test
    void withoutBpmnTheArmedEventCountsAsBeforeAndTheStructureIsReportedUnavailable() throws IOException {
        DefinitionHeat h = agg(null, recordedRows("6.8"));

        assertThat(h.structure()).isEqualTo(Structure.UNAVAILABLE);
        assertThat(node(h, "catchB").hitPct()).isEqualTo(100.0);
        assertThat(node(h, "catchB").executions()).isEqualTo(1);
        assertThat(node(h, "catchB").armedNotTaken()).isZero();
        assertThat(node(h, "catchB").totalMs()).isEqualTo(115L);
    }

    @Test
    void unparseableBpmnBehavesAsWithoutAndIsReportedUnavailable() throws IOException {
        DefinitionHeat h = agg("<definitions", recordedRows("7.1"));

        assertThat(h.structure()).isEqualTo(Structure.UNAVAILABLE);
        assertThat(node(h, "catchB").hitPct()).isEqualTo(100.0);
        assertThat(node(h, "catchB").armedNotTaken()).isZero();
    }

    @Test
    void withoutBpmnOnSixThreeNothingIsInferredSoTheNoteDoesNotClaimIt() throws IOException {
        assertThat(agg(null, recordedRows("6.3")).samplingNote()).isEqualTo(BASE_NOTE + ".");
    }

    @Test
    void modelWithoutEventGatewayIsResolvedAndUnchanged() {
        String xml = """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <process id="p"><startEvent id="s"/><sequenceFlow id="f" sourceRef="s" targetRef="t"/>
                  <userTask id="t"/></process>
                </definitions>""";
        DefinitionHeat h = agg(xml, inst(List.of(row("a", "t", "userTask", 0, 5L))));

        assertThat(h.structure()).isEqualTo(Structure.RESOLVED);
        assertThat(node(h, "t").executions()).isEqualTo(1);
        assertThat(node(h, "t").armedNotTaken()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void loopArmedTwiceTakenOnceIsOneTakenVisitAndOneArmedVisit(boolean withFlows) {
        DefinitionHeat h = agg(LOOP_BPMN, inst(loopRows(withFlows)));

        NodeHeat b = node(h, "catchB");
        assertThat(b.executions()).isEqualTo(1);
        assertThat(b.instances()).isEqualTo(1);
        assertThat(b.hitPct()).isEqualTo(100.0);
        assertThat(b.armedNotTaken()).isEqualTo(1);
        assertThat(b.totalMs()).isEqualTo(10L); // iteration 1 (taken), not iteration 2's 29 ms wait
        assertThat(b.maxMs()).isEqualTo(10L);

        NodeHeat a = node(h, "catchA");
        assertThat(a.executions()).isEqualTo(1);
        assertThat(a.armedNotTaken()).isEqualTo(1);
        assertThat(a.totalMs()).isEqualTo(30L); // iteration 2 (taken), not iteration 1's 10 ms wait
        assertThat(h.coverage()).isEqualTo(withFlows ? Coverage.FULL : Coverage.NO_FLOW_HISTORY);
    }

    @Test
    void inferredSuccessorThatStartedBeforeTheEventEndedDoesNotMakeItTaken() throws IOException {
        DefinitionHeat h = agg(
                recordedBpmn(),
                inst(List.of(
                        row("a", "start", "startEvent", 0, 0L),
                        row("a", "gw", "eventGateway", 0, 0L),
                        row("a", "catchA", "intermediateCatchEvent", 0, 10L),
                        row("a", "catchB", "intermediateCatchEvent", 0, 9L),
                        row("a", "endB", "endEvent", 5, 5L),
                        row("a", "endA", "endEvent", 10, 10L))));

        assertThat(node(h, "catchB").armedNotTaken()).isEqualTo(1);
        assertThat(node(h, "catchB").executions()).isZero();
        assertThat(node(h, "catchA").executions()).isEqualTo(1);
    }

    @Test
    void twoInstancesTakingDifferentBranchesSplitTheHitRate() throws IOException {
        Instance tookA = inst(List.of(
                row("a", "gw", "eventGateway", 0, 0L),
                row("a", "catchA", "intermediateCatchEvent", 0, 10L),
                row("a", "catchB", "intermediateCatchEvent", 0, 10L),
                row("a", "fa2", "sequenceFlow", 10, 10L),
                row("a", "endA", "endEvent", 10, 10L)));
        Instance tookB = inst(List.of(
                row("b", "gw", "eventGateway", 0, 0L),
                row("b", "catchA", "intermediateCatchEvent", 0, 40L),
                row("b", "catchB", "intermediateCatchEvent", 0, 40L),
                row("b", "fb2", "sequenceFlow", 40, 40L),
                row("b", "endB", "endEvent", 40, 40L)));

        DefinitionHeat h = agg(recordedBpmn(), tookA, tookB);

        assertThat(node(h, "catchA").hitPct()).isEqualTo(50.0);
        assertThat(node(h, "catchA").armedNotTaken()).isEqualTo(1);
        assertThat(node(h, "catchA").totalMs()).isEqualTo(10L);
        assertThat(node(h, "catchB").hitPct()).isEqualTo(50.0);
        assertThat(node(h, "catchB").armedNotTaken()).isEqualTo(1);
        assertThat(node(h, "catchB").totalMs()).isEqualTo(40L);
    }
}
