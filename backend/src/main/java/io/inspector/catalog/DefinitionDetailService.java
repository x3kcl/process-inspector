package io.inspector.catalog;

import io.inspector.action.GuardRefusedException;
import io.inspector.catalog.DefinitionDetailResponse.VersionRef;
import io.inspector.catalog.DefinitionJobsResponse.JobLaneCount;
import io.inspector.catalog.DefinitionSubscriptionsResponse.SubscriptionRow;
import io.inspector.client.CaseDefinitionRecord;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.EventSubscriptionPage;
import io.inspector.client.EventSubscriptionRecord;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.JobPage;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessApiClient.JobLaneKind;
import io.inspector.client.ProcessDefinitionPage;
import io.inspector.client.ProcessDefinitionRecord;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Read-only definition detail: metadata, XML, subscriptions, job-lane counts, and the BPMN diff. */
@Service
public class DefinitionDetailService {

    private static final int SUBSCRIPTION_CAP = 100;

    private final EngineRegistry registry;
    private final ProcessApiClient processes;
    private final CmmnApiClient cases;

    public DefinitionDetailService(EngineRegistry registry, ProcessApiClient processes, CmmnApiClient cases) {
        this.registry = registry;
        this.processes = processes;
        this.cases = cases;
    }

    public DefinitionDetailResponse detail(String engineId, String definitionId) {
        EngineConfig engine = registry.require(engineId);
        ProcessDefinitionRecord bpmn = processes.getProcessDefinition(engine, CallPriority.INTERACTIVE, definitionId);
        if (bpmn != null) {
            return bpmnDetail(engine, bpmn);
        }
        if (cmmn(engine)) {
            CaseDefinitionRecord cmmn = cases.getCmmnCaseDefinition(engine, CallPriority.INTERACTIVE, definitionId);
            if (cmmn != null) {
                return cmmnDetail(engine, cmmn);
            }
        }
        throw notFound(engineId, definitionId);
    }

    public DefinitionXmlResponse xml(String engineId, String definitionId) {
        EngineConfig engine = registry.require(engineId);
        String xml = processes.processDefinitionResourceData(engine, CallPriority.INTERACTIVE, definitionId);
        return new DefinitionXmlResponse(xml);
    }

    public DefinitionSubscriptionsResponse subscriptions(String engineId, String definitionId) {
        EngineConfig engine = registry.require(engineId);
        if (processes.getProcessDefinition(engine, CallPriority.INTERACTIVE, definitionId) == null) {
            if (cmmn(engine) && cases.getCmmnCaseDefinition(engine, CallPriority.INTERACTIVE, definitionId) != null) {
                return new DefinitionSubscriptionsResponse("CMMN", List.of(), true);
            }
            throw notFound(engineId, definitionId);
        }
        EventSubscriptionPage page = processes.listDefinitionEventSubscriptions(
                engine, CallPriority.INTERACTIVE, definitionId, SUBSCRIPTION_CAP);
        List<SubscriptionRow> rows = new ArrayList<>();
        for (EventSubscriptionRecord row : page.dataOrEmpty()) {
            String instanceId = row.processInstanceId();
            rows.add(new SubscriptionRow(
                    row.id(), row.eventType(), row.eventName(), row.activityId(), instanceId, instanceId == null));
        }
        return new DefinitionSubscriptionsResponse("BPMN", rows, page.total() <= rows.size());
    }

    public DefinitionJobsResponse jobs(String engineId, String definitionId) {
        EngineConfig engine = registry.require(engineId);
        if (processes.getProcessDefinition(engine, CallPriority.INTERACTIVE, definitionId) == null) {
            if (cmmn(engine) && cases.getCmmnCaseDefinition(engine, CallPriority.INTERACTIVE, definitionId) != null) {
                return new DefinitionJobsResponse("CMMN", List.of());
            }
            throw notFound(engineId, definitionId);
        }
        List<JobLaneCount> lanes = new ArrayList<>();
        for (JobLaneKind lane : JobLaneKind.values()) {
            try {
                JobPage page = processes.listJobs(
                        engine, CallPriority.INTERACTIVE, lane, Map.of("processDefinitionId", definitionId), 0, 1);
                lanes.add(new JobLaneCount(lane.name().toLowerCase(), page != null ? page.total() : 0, false));
            } catch (RuntimeException e) {
                lanes.add(new JobLaneCount(lane.name().toLowerCase(), 0, true));
            }
        }
        return new DefinitionJobsResponse("BPMN", lanes);
    }

    public BpmnDiffResponse diff(String engineId, String leftId, String rightId) {
        EngineConfig engine = registry.require(engineId);
        String left = processes.processDefinitionResourceData(engine, CallPriority.INTERACTIVE, leftId);
        String right = processes.processDefinitionResourceData(engine, CallPriority.INTERACTIVE, rightId);
        if (left == null || right == null) {
            throw new GuardRefusedException(
                    HttpStatus.NOT_FOUND,
                    "definition-not-found",
                    "Both definitions must be deployed BPMN on '" + engineId + "'.");
        }
        try {
            return BpmnModelDiff.diff(engineId, leftId, left, rightId, right);
        } catch (IllegalArgumentException e) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_GATEWAY,
                    "bpmn-unreadable",
                    "The engine returned BPMN this inspector could not parse.");
        }
    }

    private DefinitionDetailResponse bpmnDetail(EngineConfig engine, ProcessDefinitionRecord row) {
        String key = row.key();
        List<VersionRef> versions = List.of();
        if (key != null) {
            ProcessDefinitionPage page = processes.listProcessDefinitionVersionsDesc(
                    engine, CallPriority.INTERACTIVE, key, 0, DefinitionCatalogService.LIST_CAP);
            int latest = page.dataOrEmpty().isEmpty()
                    ? number(row.version())
                    : number(page.dataOrEmpty().get(0).version());
            versions = new ArrayList<>();
            for (ProcessDefinitionRecord version : page.dataOrEmpty()) {
                int n = number(version.version());
                versions.add(new VersionRef(version.id(), n, n == latest));
            }
            versions.sort(Comparator.comparingInt(VersionRef::version).reversed());
        }
        return new DefinitionDetailResponse(
                engine.id(),
                "BPMN",
                row.id(),
                key,
                row.name(),
                number(row.version()),
                bool(row.suspended()),
                row.deploymentId(),
                row.category(),
                row.tenantId() != null ? row.tenantId() : engine.tenantId(),
                row.resourceName(),
                bool(row.graphicalNotationDefined()),
                versions);
    }

    private DefinitionDetailResponse cmmnDetail(EngineConfig engine, CaseDefinitionRecord row) {
        return new DefinitionDetailResponse(
                engine.id(),
                "CMMN",
                row.id(),
                row.key(),
                row.name(),
                number(row.version()),
                bool(row.suspended()),
                row.deploymentId(),
                row.category(),
                row.tenantId() != null ? row.tenantId() : engine.tenantId(),
                row.resourceName(),
                bool(row.graphicalNotationDefined()),
                List.of());
    }

    private boolean cmmn(EngineConfig engine) {
        EngineCapabilities capabilities = registry.healthOf(engine.id()).capabilities();
        return capabilities != null && capabilities.scopeType();
    }

    private static GuardRefusedException notFound(String engineId, String definitionId) {
        return new GuardRefusedException(
                HttpStatus.NOT_FOUND,
                "definition-not-found",
                "Definition " + definitionId + " does not exist on '" + engineId + "'.");
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private static int number(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static boolean bool(Object value) {
        return Boolean.TRUE.equals(value) || "true".equals(String.valueOf(value));
    }
}
