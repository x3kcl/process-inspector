package io.inspector.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/** Test fixture: build typed CMMN rows from the wire-keyed maps the tests already read as JSON. */
public final class CmmnRecords {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CmmnRecords() {}

    public static CaseInstanceRecord caseInstance(Map<String, ?> wire) {
        return MAPPER.convertValue(wire, CaseInstanceRecord.class);
    }

    public static CaseInstancePage casePage(List<? extends Map<String, ?>> wire, long total) {
        return new CaseInstancePage(wire.stream().map(CmmnRecords::caseInstance).toList(), total, 0, wire.size());
    }

    public static PlanItemRecord planItem(Map<String, ?> wire) {
        return MAPPER.convertValue(wire, PlanItemRecord.class);
    }

    public static List<PlanItemRecord> planItems(List<? extends Map<String, ?>> wire) {
        return wire.stream().map(CmmnRecords::planItem).toList();
    }

    public static PlanItemPage planItemPage(List<? extends Map<String, ?>> wire, long total) {
        return new PlanItemPage(planItems(wire), total, 0, wire.size());
    }

    public static CmmnJobRecord job(Map<String, ?> wire) {
        return MAPPER.convertValue(wire, CmmnJobRecord.class);
    }

    public static CmmnJobPage jobPage(List<? extends Map<String, ?>> wire, long total) {
        return new CmmnJobPage(wire.stream().map(CmmnRecords::job).toList(), total, 0, wire.size());
    }
}
