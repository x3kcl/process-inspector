package io.inspector.heat;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.client.ActivityInstanceRecord;
import io.inspector.dto.DefinitionHeat;
import io.inspector.dto.DefinitionHeat.NodeHeat;
import io.inspector.dto.DefinitionHeat.Structure;
import io.inspector.heat.HeatAggregator.Instance;
import io.inspector.heat.HeatAggregator.Sample;
import io.inspector.surgery.BpmnStructure;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Review of decision A (#783): model shapes the first tests did not cover. {@code gw} arms
 * {@code A} (message) and {@code B} (timer); what follows each varies per test. Rows are on a
 * millisecond clock; {@code withFlows} adds the route rows a 6.4+ engine writes.
 */
class HeatAggregatorEventGatewayEdgeTest {

    private static final String INFERRED = "event-gateway branches inferred from timing";

    private static String model(String rest) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                  <process id="p" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f0" sourceRef="start" targetRef="gw"/>
                    <eventBasedGateway id="gw"/>
                    <sequenceFlow id="fa" sourceRef="gw" targetRef="A"/>
                    <sequenceFlow id="fb" sourceRef="gw" targetRef="B"/>
                    <intermediateCatchEvent id="A"><messageEventDefinition/></intermediateCatchEvent>
                    <intermediateCatchEvent id="B"><timerEventDefinition/></intermediateCatchEvent>
                """ + rest + """
                  </process>
                </definitions>""";
    }

    private static ActivityInstanceRecord row(String act, String type, long start, Long end) {
        Instant t0 = Instant.parse("2026-11-17T10:00:00Z");
        return new ActivityInstanceRecord(
                act + "-" + start,
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
                end == null ? null : t0.plusMillis(end).toString(),
                end == null ? null : end - start,
                "");
    }

    private static ActivityInstanceRecord flow(String id, long at) {
        return row(id, "sequenceFlow", at, at);
    }

    private static DefinitionHeat agg(String xml, List<ActivityInstanceRecord> rows) {
        return HeatAggregator.aggregate(new Sample(
                "e1",
                "def:1",
                Instant.EPOCH,
                200,
                1,
                null,
                null,
                List.of(new Instance(rows, rows.size(), false)),
                xml));
    }

    private static NodeHeat node(DefinitionHeat h, String id) {
        return h.nodes().stream()
                .filter(n -> n.activityId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no node " + id + " in " + h.nodes()));
    }

    /** A forks implicitly (two outgoing flows); B loops back through t to gw. */
    private static final String FORK_LOOP = model("""
                <sequenceFlow id="fa2" sourceRef="A" targetRef="e1"/>
                <sequenceFlow id="fa3" sourceRef="A" targetRef="e2"/>
                <endEvent id="e1"/>
                <endEvent id="e2"/>
                <sequenceFlow id="fb2" sourceRef="B" targetRef="t"/>
                <userTask id="t"/>
                <sequenceFlow id="ft" sourceRef="t" targetRef="gw"/>
            """);

    /** Visit 1: B fires at 10 (A armed 0→10). Visit 2: A fires at 50 (B armed 20→49), both forks run. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void eventWithTwoOutgoingFlowsInALoopCountsTheArmedVisitAsArmed(boolean withFlows) {
        List<ActivityInstanceRecord> rows = new ArrayList<>(List.of(
                row("start", "startEvent", 0, 0L),
                row("gw", "eventGateway", 0, 0L),
                row("A", "intermediateCatchEvent", 0, 10L),
                row("B", "intermediateCatchEvent", 0, 10L),
                row("t", "userTask", 10, 20L),
                row("gw", "eventGateway", 20, 20L),
                row("A", "intermediateCatchEvent", 20, 50L),
                row("B", "intermediateCatchEvent", 20, 49L),
                row("e1", "endEvent", 50, 50L),
                row("e2", "endEvent", 50, 50L)));
        if (withFlows) {
            rows.addAll(List.of(
                    flow("f0", 0),
                    flow("fa", 0),
                    flow("fb", 0),
                    flow("fb2", 10),
                    flow("ft", 20),
                    flow("fa", 20),
                    flow("fb", 20),
                    flow("fa2", 50),
                    flow("fa3", 50)));
        }
        DefinitionHeat h = agg(FORK_LOOP, rows);

        assertThat(node(h, "A").executions()).isEqualTo(1);
        assertThat(node(h, "A").armedNotTaken()).isEqualTo(1);
        assertThat(node(h, "A").totalMs()).isEqualTo(30L);
        assertThat(node(h, "B").executions()).isEqualTo(1);
        assertThat(node(h, "B").armedNotTaken()).isEqualTo(1);
    }

    /** Both branches merge at one exclusive gateway j: one j row can stand for ONE event row only. */
    @Test
    void sharedDirectSuccessorWithoutRoutesTakesOnlyOneEvent() {
        String xml = model("""
                    <sequenceFlow id="fa2" sourceRef="A" targetRef="j"/>
                    <sequenceFlow id="fb2" sourceRef="B" targetRef="j"/>
                    <exclusiveGateway id="j"/>
                    <sequenceFlow id="fj" sourceRef="j" targetRef="end"/>
                    <endEvent id="end"/>
                """);
        // As recorded (wire/*/evgw): the armed event ends just before the one that fired.
        DefinitionHeat h = agg(
                xml,
                List.of(
                        row("start", "startEvent", 0, 0L),
                        row("gw", "eventGateway", 0, 0L),
                        row("A", "intermediateCatchEvent", 0, 100L),
                        row("B", "intermediateCatchEvent", 0, 99L),
                        row("j", "exclusiveGateway", 100, 100L),
                        row("end", "endEvent", 100, 100L)));

        assertThat(node(h, "A").executions()).isEqualTo(1);
        assertThat(node(h, "B").executions()).isZero();
        assertThat(node(h, "B").armedNotTaken()).isEqualTo(1);
        assertThat(node(h, "A").sharePct()).isEqualTo(100.0);
        assertThat(h.samplingNote()).contains(INFERRED);
    }

    /**
     * A catch event with no outgoing flow (implicit end) gives nothing to resolve by: the
     * definition is counted as without BPMN and says so, never a fired event counted as armed.
     */
    @Test
    void catchEventWithoutOutgoingFlowFallsBackToUnavailable() {
        String xml = model("""
                    <sequenceFlow id="fb2" sourceRef="B" targetRef="end"/>
                    <endEvent id="end"/>
                """);
        DefinitionHeat h = agg(
                xml,
                List.of(
                        row("start", "startEvent", 0, 0L),
                        flow("f0", 0),
                        row("gw", "eventGateway", 0, 0L),
                        flow("fa", 0),
                        flow("fb", 0),
                        row("A", "intermediateCatchEvent", 0, 100L),
                        row("B", "intermediateCatchEvent", 0, 99L)));

        assertThat(h.structure()).isEqualTo(Structure.UNAVAILABLE);
        assertThat(node(h, "A").executions()).isEqualTo(1);
        assertThat(node(h, "B").executions()).isEqualTo(1);
    }

    /** A is also reached by a normal flow from t: that visit fires and is taken. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void catchEventAlsoReachedByANormalFlowCountsBothVisitsRight(boolean withFlows) {
        String xml = model("""
                    <sequenceFlow id="fa2" sourceRef="A" targetRef="end"/>
                    <endEvent id="end"/>
                    <sequenceFlow id="fb2" sourceRef="B" targetRef="t"/>
                    <userTask id="t"/>
                    <sequenceFlow id="ft" sourceRef="t" targetRef="A"/>
                """);
        List<ActivityInstanceRecord> rows = new ArrayList<>(List.of(
                row("gw", "eventGateway", 0, 0L),
                row("A", "intermediateCatchEvent", 0, 10L),
                row("B", "intermediateCatchEvent", 0, 10L),
                row("t", "userTask", 10, 20L),
                row("A", "intermediateCatchEvent", 20, 50L),
                row("end", "endEvent", 50, 50L)));
        if (withFlows) {
            rows.addAll(List.of(flow("fa", 0), flow("fb", 0), flow("fb2", 10), flow("ft", 20), flow("fa2", 50)));
        }
        DefinitionHeat h = agg(xml, rows);

        assertThat(node(h, "A").executions()).isEqualTo(1);
        assertThat(node(h, "A").armedNotTaken()).isEqualTo(1);
        assertThat(node(h, "A").totalMs()).isEqualTo(30L);
        assertThat(node(h, "B").executions()).isEqualTo(1);
    }

    /** Taken A leads to a parallel fork whose row starts in the very millisecond A ended. */
    @Test
    void successorStartingInTheSameMillisecondMakesTheEventTaken() {
        String xml = model("""
                    <sequenceFlow id="fa2" sourceRef="A" targetRef="fork"/>
                    <parallelGateway id="fork"/>
                    <sequenceFlow id="fb2" sourceRef="B" targetRef="end"/>
                    <endEvent id="end"/>
                """);
        DefinitionHeat h = agg(
                xml,
                List.of(
                        row("A", "intermediateCatchEvent", 0, 40L),
                        row("B", "intermediateCatchEvent", 0, 40L),
                        row("fork", "parallelGateway", 40, 40L)));

        assertThat(node(h, "A").executions()).isEqualTo(1);
        assertThat(node(h, "B").armedNotTaken()).isEqualTo(1);
    }

    /** Rows out of time order, an unfinished armed row, a zero-length timer: same answer. */
    @Test
    void outOfOrderRowsUnfinishedArmedRowAndZeroLengthTimer() {
        String xml = model("""
                    <sequenceFlow id="fa2" sourceRef="A" targetRef="end"/>
                    <sequenceFlow id="fb2" sourceRef="B" targetRef="endB"/>
                    <endEvent id="end"/>
                    <endEvent id="endB"/>
                """);
        List<ActivityInstanceRecord> rows = new ArrayList<>(List.of(
                row("endB", "endEvent", 5, 5L),
                row("B", "intermediateCatchEvent", 5, 5L),
                row("A", "intermediateCatchEvent", 5, null),
                row("gw", "eventGateway", 5, 5L)));
        Collections.reverse(rows);
        DefinitionHeat h = agg(xml, rows);

        assertThat(node(h, "B").executions()).isEqualTo(1);
        assertThat(node(h, "A").executions()).isZero();
        assertThat(node(h, "A").armedNotTaken()).isEqualTo(1);
    }

    /** Boundary events and event-subprocess starts are not armed by a gateway; a receive task is. */
    @Test
    void onlyNodesAGatewayArmsAreCatches() {
        BpmnStructure s = BpmnStructure.fromXml(model("""
                    <sequenceFlow id="fr" sourceRef="gw" targetRef="rt"/>
                    <receiveTask id="rt"/>
                    <sequenceFlow id="fr2" sourceRef="rt" targetRef="end"/>
                    <boundaryEvent id="bd" attachedToRef="rt"><timerEventDefinition/></boundaryEvent>
                    <sequenceFlow id="fbd" sourceRef="bd" targetRef="end"/>
                    <subProcess id="esp" triggeredByEvent="true">
                      <startEvent id="es"><messageEventDefinition/></startEvent>
                    </subProcess>
                    <sequenceFlow id="fa2" sourceRef="A" targetRef="end"/>
                    <sequenceFlow id="fb2" sourceRef="B" targetRef="end"/>
                    <endEvent id="end"/>
                """));

        assertThat(s.eventGatewayCatches().keySet()).containsExactlyInAnyOrder("A", "B", "rt");
    }

    /**
     * The 6.3 inference's known blind spot: A's direct successor x is ALSO reached via B → y → x,
     * so the armed A looks taken. That misclassification is allowed only because it is marked.
     */
    @Test
    void successorReachedByAnotherPathIsMisreadButMarkedInferred() {
        String xml = model("""
                    <sequenceFlow id="fa2" sourceRef="A" targetRef="x"/>
                    <sequenceFlow id="fb2" sourceRef="B" targetRef="y"/>
                    <userTask id="y"/>
                    <sequenceFlow id="fy" sourceRef="y" targetRef="x"/>
                    <userTask id="x"/>
                """);
        DefinitionHeat h = agg(
                xml,
                List.of(
                        row("gw", "eventGateway", 0, 0L),
                        row("A", "intermediateCatchEvent", 0, 99L),
                        row("B", "intermediateCatchEvent", 0, 100L),
                        row("y", "userTask", 100, 200L),
                        row("x", "userTask", 200, 300L)));

        assertThat(node(h, "A").executions()).isEqualTo(1);
        assertThat(h.samplingNote()).contains(INFERRED);
    }
}
