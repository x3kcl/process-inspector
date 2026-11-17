package io.inspector.client;

import java.util.List;
import java.util.Map;

/**
 * Test fixture: build typed variable rows from the wire-keyed maps the tests already hold. Built by
 * hand, NOT through a Jackson round trip — the value must stay the very object the test passed
 * (a {@code Long} stays a {@code Long}).
 */
public final class VariableRecords {

    private VariableRecords() {}

    public static VariableRecord variable(Map<String, ?> wire) {
        return new VariableRecord(
                str(wire, "name"), str(wire, "type"), wire.get("value"), str(wire, "scope"), str(wire, "valueUrl"));
    }

    /** One historic row: the envelope nested under {@code variable}, as the engines send it. */
    public static HistoricVariableRecord historicRow(Map<String, ?> wire) {
        Object nested = wire.get("variable");
        return new HistoricVariableRecord(
                str(wire, "id"),
                str(wire, "processInstanceId"),
                str(wire, "executionId"),
                str(wire, "taskId"),
                nested instanceof Map<?, ?> m ? variable(stringKeyed(m)) : null);
    }

    public static HistoricVariablePage historicPage(List<? extends Map<String, ?>> wire, long total, int size) {
        return new HistoricVariablePage(
                wire.stream().map(VariableRecords::historicRow).toList(), total, 0, size);
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> map) {
        Map<String, Object> out = new java.util.HashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static String str(Map<String, ?> wire, String key) {
        Object v = wire.get(key);
        return v != null ? v.toString() : null;
    }
}
