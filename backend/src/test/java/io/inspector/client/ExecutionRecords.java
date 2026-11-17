package io.inspector.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/**
 * Test fixture: build typed execution, historic-activity and event-subscription rows from the
 * wire-keyed maps the tests already read as JSON.
 */
public final class ExecutionRecords {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExecutionRecords() {}

    public static ExecutionRecord execution(Map<String, ?> wire) {
        return MAPPER.convertValue(wire, ExecutionRecord.class);
    }

    public static List<ExecutionRecord> executions(List<? extends Map<String, ?>> wire) {
        return wire.stream().map(ExecutionRecords::execution).toList();
    }

    public static ExecutionPage executionPage(List<? extends Map<String, ?>> wire, long total) {
        return new ExecutionPage(executions(wire), total, 0, wire.size());
    }

    public static ActivityInstanceRecord activity(Map<String, ?> wire) {
        return MAPPER.convertValue(wire, ActivityInstanceRecord.class);
    }

    public static ActivityInstancePage activityPage(List<? extends Map<String, ?>> wire, long total) {
        return new ActivityInstancePage(
                wire.stream().map(ExecutionRecords::activity).toList(), total, 0, wire.size());
    }

    public static EventSubscriptionPage subscriptionPage(List<? extends Map<String, ?>> wire, long total) {
        return new EventSubscriptionPage(
                wire.stream()
                        .map(row -> MAPPER.convertValue(row, EventSubscriptionRecord.class))
                        .toList(),
                total,
                0,
                wire.size());
    }
}
