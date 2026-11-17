package io.inspector.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/** Test fixture: build typed job rows from the wire-keyed maps the tests already read as JSON. */
public final class JobRecords {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JobRecords() {}

    public static JobRecord job(Map<String, ?> wire) {
        return MAPPER.convertValue(wire, JobRecord.class);
    }

    public static List<JobRecord> jobs(List<? extends Map<String, ?>> wire) {
        return wire.stream().map(JobRecords::job).toList();
    }

    public static JobPage page(List<? extends Map<String, ?>> wire) {
        return new JobPage(jobs(wire), wire.size(), 0, wire.size());
    }
}
