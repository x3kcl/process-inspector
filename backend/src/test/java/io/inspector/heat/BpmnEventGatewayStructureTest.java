package io.inspector.heat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.inspector.surgery.BpmnStructure;
import io.inspector.surgery.BpmnStructure.Flow;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rung 1, pure: which nodes an event-based gateway ARMS, read from the BPMN XML (#783, owner
 * decision A of 2026-11-18). Reuses the surgery parser ({@link BpmnStructure}), XXE-hardened.
 */
class BpmnEventGatewayStructureTest {

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"t\">\n";

    private static String recordedBpmn() throws IOException {
        try (InputStream in = BpmnEventGatewayStructureTest.class.getResourceAsStream("/heat/heatEvgw.bpmn20.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void recordedModelArmsBothCatchEventsWithTheirOutgoingFlows() throws IOException {
        Map<String, List<Flow>> catches = BpmnStructure.fromXml(recordedBpmn()).eventGatewayCatches();

        assertThat(catches)
                .containsOnlyKeys("catchA", "catchB")
                .containsEntry("catchA", List.of(new Flow("fa2", "endA")))
                .containsEntry("catchB", List.of(new Flow("fb2", "endB")));
    }

    @Test
    void receiveTaskAndTimerAfterTheGatewayAreArmedAPlainCatchEventIsNot() {
        String xml = HEAD + """
                <process id="p" isExecutable="true">
                  <startEvent id="s"/>
                  <sequenceFlow id="f0" sourceRef="s" targetRef="plain"/>
                  <intermediateCatchEvent id="plain"><timerEventDefinition/></intermediateCatchEvent>
                  <sequenceFlow id="f1" sourceRef="plain" targetRef="gw"/>
                  <eventBasedGateway id="gw"/>
                  <sequenceFlow id="g1" sourceRef="gw" targetRef="rt"/>
                  <sequenceFlow id="g2" sourceRef="gw" targetRef="timer"/>
                  <receiveTask id="rt"/>
                  <intermediateCatchEvent id="timer"><timerEventDefinition/></intermediateCatchEvent>
                  <sequenceFlow id="r1" sourceRef="rt" targetRef="e1"/>
                  <sequenceFlow id="r2" sourceRef="rt" targetRef="e2"/>
                  <sequenceFlow id="t1" sourceRef="timer" targetRef="e2"/>
                  <endEvent id="e1"/>
                  <endEvent id="e2"/>
                </process>
                </definitions>""";

        Map<String, List<Flow>> catches = BpmnStructure.fromXml(xml).eventGatewayCatches();

        assertThat(catches).containsOnlyKeys("rt", "timer");
        assertThat(catches.get("rt")).containsExactly(new Flow("r1", "e1"), new Flow("r2", "e2"));
        assertThat(catches.get("timer")).containsExactly(new Flow("t1", "e2"));
    }

    @Test
    void gatewayInsideAnEmbeddedSubprocessAndInASecondPool() {
        String xml = HEAD + """
                <collaboration id="c">
                  <participant id="pa" processRef="p1"/>
                  <participant id="pb" processRef="p2"/>
                </collaboration>
                <process id="p1" isExecutable="true">
                  <startEvent id="s1"/>
                  <sequenceFlow id="a0" sourceRef="s1" targetRef="sub"/>
                  <subProcess id="sub">
                    <startEvent id="ss"/>
                    <sequenceFlow id="b0" sourceRef="ss" targetRef="innerGw"/>
                    <eventBasedGateway id="innerGw"/>
                    <sequenceFlow id="b1" sourceRef="innerGw" targetRef="innerCatch"/>
                    <intermediateCatchEvent id="innerCatch"><signalEventDefinition/></intermediateCatchEvent>
                    <sequenceFlow id="b2" sourceRef="innerCatch" targetRef="se"/>
                    <endEvent id="se"/>
                  </subProcess>
                  <sequenceFlow id="a1" sourceRef="sub" targetRef="e1"/>
                  <endEvent id="e1"/>
                </process>
                <process id="p2" isExecutable="false">
                  <startEvent id="s2"/>
                  <sequenceFlow id="c0" sourceRef="s2" targetRef="gw2"/>
                  <eventBasedGateway id="gw2"/>
                  <sequenceFlow id="c1" sourceRef="gw2" targetRef="otherCatch"/>
                  <intermediateCatchEvent id="otherCatch"><messageEventDefinition/></intermediateCatchEvent>
                  <sequenceFlow id="c2" sourceRef="otherCatch" targetRef="e2"/>
                  <endEvent id="e2"/>
                </process>
                </definitions>""";

        Map<String, List<Flow>> catches = BpmnStructure.fromXml(xml).eventGatewayCatches();

        assertThat(catches)
                .containsOnlyKeys("innerCatch", "otherCatch")
                .containsEntry("innerCatch", List.of(new Flow("b2", "se")))
                .containsEntry("otherCatch", List.of(new Flow("c2", "e2")));
    }

    @Test
    void modelWithoutAnEventGatewayArmsNothing() {
        String xml = HEAD + """
                <process id="p" isExecutable="true">
                  <startEvent id="s"/>
                  <sequenceFlow id="f0" sourceRef="s" targetRef="c"/>
                  <intermediateCatchEvent id="c"><timerEventDefinition/></intermediateCatchEvent>
                  <sequenceFlow id="f1" sourceRef="c" targetRef="e"/>
                  <endEvent id="e"/>
                </process>
                </definitions>""";

        assertThat(BpmnStructure.fromXml(xml).eventGatewayCatches()).isEmpty();
    }

    @Test
    void doctypeWithAnExternalEntityIsRefusedNotFetched(@TempDir Path dir) throws IOException {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "LEAKED-MARKER");
        String xml = "<?xml version=\"1.0\"?>\n<!DOCTYPE definitions [<!ENTITY x SYSTEM \""
                + secret.toUri() + "\">]>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\">"
                + "<process id=\"&x;\"/></definitions>";

        assertThatThrownBy(() -> BpmnStructure.fromXml(xml))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DOCTYPE")
                .hasMessageNotContaining("LEAKED-MARKER");
    }

    @Test
    void malformedXmlIsRefused() {
        assertThatThrownBy(() -> BpmnStructure.fromXml("<definitions"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not parseable");
    }
}
