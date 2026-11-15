package io.inspector.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/** Test fixture: build typed process-instance rows from the wire-keyed maps the tests already read as JSON. */
public final class ProcessInstanceRecords {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProcessInstanceRecords() {}

    public static ProcessInstanceRecord instance(Map<String, ?> wire) {
        return MAPPER.convertValue(wire, ProcessInstanceRecord.class);
    }

    public static List<ProcessInstanceRecord> instances(List<? extends Map<String, ?>> wire) {
        return wire.stream().map(ProcessInstanceRecords::instance).toList();
    }

    public static ProcessInstancePage page(List<? extends Map<String, ?>> wire) {
        return page(wire, wire.size());
    }

    public static ProcessInstancePage page(List<? extends Map<String, ?>> wire, long total) {
        return new ProcessInstancePage(instances(wire), total, 0, wire.size());
    }
}
