package io.inspector.detail;

import io.inspector.client.DmnApiClient;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Instance-detail reads added for Hub parity: participants, execution tree, DMN decisions. */
@Service
public class InstanceParityService {

    private static final int PAGE = 50;

    private final EngineRegistry registry;
    private final ProcessApiClient flowable;
    private final DmnApiClient dmn;

    public InstanceParityService(EngineRegistry registry, ProcessApiClient flowable, DmnApiClient dmn) {
        this.registry = registry;
        this.flowable = flowable;
        this.dmn = dmn;
    }

    public ParticipantPage participants(String engineId, String instanceId) {
        EngineConfig engine = registry.require(engineId);
        Map<String, Object> historic =
                flowable.getHistoricProcessInstance(engine, CallPriority.INTERACTIVE, instanceId);
        if (historic == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown instance: " + instanceId);
        }
        boolean running = flowable.getRuntimeProcessInstance(engine, CallPriority.INTERACTIVE, instanceId) != null;
        List<Map<String, Object>> raw = running
                ? flowable.listRuntimeIdentityLinks(engine, CallPriority.INTERACTIVE, instanceId)
                : flowable.listHistoricIdentityLinks(engine, CallPriority.INTERACTIVE, instanceId);
        List<ParticipantRow> rows = new ArrayList<>();
        for (Map<String, Object> link : raw) {
            String user = first(link, "user", "userId");
            if (user == null) {
                continue;
            }
            String type = first(link, "type");
            rows.add(new ParticipantRow(user, type == null ? "participant" : type, !"starter".equals(type)));
        }
        String starter = first(historic, "startUserId");
        if (starter != null
                && rows.stream().noneMatch(row -> starter.equals(row.user()) && "starter".equals(row.type()))) {
            rows.add(0, new ParticipantRow(starter, "starter", false));
        }
        return new ParticipantPage(running, rows);
    }

    public ExecutionTreeBuilder.ExecutionTree executions(String engineId, String instanceId) {
        EngineConfig engine = registry.require(engineId);
        FlowablePage page = flowable.listExecutions(engine, CallPriority.INTERACTIVE, instanceId, PAGE);
        List<Map<String, Object>> data = page == null ? List.of() : page.dataOrEmpty();
        boolean capped = page != null && page.total() > data.size();
        return ExecutionTreeBuilder.build(data, capped);
    }

    public DecisionPage decisions(String engineId, String instanceId) {
        EngineConfig engine = registry.require(engineId);
        EngineCapabilities caps = registry.healthOf(engineId).capabilities();
        if (caps != null && !caps.dmnHistory()) {
            return DecisionPage.absent();
        }
        FlowablePage page = dmn.listByProcessInstance(engine, CallPriority.INTERACTIVE, instanceId, PAGE);
        if (page == null) {
            return DecisionPage.absent();
        }
        List<DecisionRow> rows = new ArrayList<>();
        for (Map<String, Object> row : page.dataOrEmpty()) {
            rows.add(new DecisionRow(
                    str(row, "id"),
                    first(row, "decisionKey", "decisionDefinitionKey"),
                    str(row, "activityId"),
                    Boolean.TRUE.equals(row.get("failed")),
                    str(row, "startTime"),
                    str(row, "endTime")));
        }
        return new DecisionPage(true, rows, page.total() > rows.size());
    }

    public Map<String, Object> decisionAudit(String engineId, String decisionExecutionId) {
        EngineConfig engine = registry.require(engineId);
        Map<String, Object> audit = dmn.auditData(engine, CallPriority.INTERACTIVE, decisionExecutionId);
        return audit == null ? Map.of() : new LinkedHashMap<>(audit);
    }

    private static String str(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private static String first(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            String value = str(row, key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record ParticipantPage(boolean running, List<ParticipantRow> rows) {}

    public record ParticipantRow(String user, String type, boolean removable) {}

    public record DecisionPage(boolean available, List<DecisionRow> rows, boolean capped) {
        static DecisionPage absent() {
            return new DecisionPage(false, List.of(), false);
        }
    }

    public record DecisionRow(
            String id, String decisionKey, String activityId, boolean failed, String startTime, String endTime) {}
}
