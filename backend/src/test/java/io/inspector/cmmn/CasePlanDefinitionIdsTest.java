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
}
