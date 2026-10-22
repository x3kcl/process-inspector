package io.inspector.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BpmnModelDiffTest {

    private static final String LEFT = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
              <process id="p" name="left">
                <startEvent id="start" name="Start"/>
                <userTask id="approve" name="Approve"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
              </process>
            </definitions>
            """;

    private static final String RIGHT = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
              <process id="p" name="right">
                <startEvent id="start" name="Start"/>
                <userTask id="approve" name="Review"/>
                <serviceTask id="notify" name="Notify"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
              </process>
            </definitions>
            """;

    @Test
    void diffsAddedRemovedAndChangedById() {
        BpmnDiffResponse diff = BpmnModelDiff.diff("engine-a", "left", LEFT, "right", RIGHT);

        assertThat(diff.added()).extracting(BpmnDiffResponse.ElementChange::id).containsExactly("notify");
        assertThat(diff.removed()).isEmpty();
        assertThat(diff.changed())
                .extracting(BpmnDiffResponse.ElementChange::id)
                .containsExactly("p", "approve");
        assertThat(diff.changed())
                .filteredOn(c -> c.id().equals("approve"))
                .singleElement()
                .extracting(BpmnDiffResponse.ElementChange::detail)
                .isEqualTo("name");
    }
}
