package io.inspector.cmmn;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CasePlanDefinitionIdsTest {

    @Test
    void comparesPlanItemDefinitionIdsNotInstanceOrElementIds() {
        List<Map<String, Object>> rows = List.of(
                Map.of(
                        "id",
                        "inst-1",
                        "elementId",
                        "planItem_svc",
                        "planItemDefinitionId",
                        "keepAlive",
                        "state",
                        "active"),
                Map.of(
                        "id",
                        "inst-2",
                        "elementId",
                        "planItem_old",
                        "planItemDefinitionId",
                        "removedTask",
                        "state",
                        "active"),
                Map.of(
                        "id",
                        "inst-3",
                        "elementId",
                        "planItem_done",
                        "planItemDefinitionId",
                        "finished",
                        "state",
                        "completed"));
        String xml = "<planItem id=\"planItem_svc\" definitionRef=\"keepAlive\"/>";

        assertThat(CasePlanDefinitionIds.missing(
                        CasePlanDefinitionIds.activeDefinitionIds(rows), CasePlanDefinitionIds.definitionIds(xml)))
                .containsExactly("removedTask");
    }

    /** XML allows either quote (#500 review): a single-quoted model must not read as empty. */
    @Test
    void readsSingleQuotedAndSpacedDefinitionRefs() {
        String xml = "<planItem id='p1' definitionRef='reviewTask'/>"
                + "<planItem id=\"p2\" definitionRef = \"optionalNote\"/>"
                + "<planItem id='p3' definitionRef=\n  'it\"s'/>";

        assertThat(CasePlanDefinitionIds.definitionIds(xml)).containsExactly("reviewTask", "optionalNote", "it\"s");
    }
}
