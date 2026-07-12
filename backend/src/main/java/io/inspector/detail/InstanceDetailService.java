package io.inspector.detail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.action.GuardRefusedException;
import io.inspector.audit.ProtectedInstance;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.ExternalJobApiClient;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessApiClient.JobLaneKind;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.ExternalWorkerJobDto;
import io.inspector.dto.InstanceDetail;
import io.inspector.dto.InstanceDetail.CurrentActivity;
import io.inspector.dto.InstanceDetail.WaitState;
import io.inspector.dto.InstanceDetail.WhyStuck;
import io.inspector.dto.InstanceDiagram;
import io.inspector.dto.InstanceHierarchy;
import io.inspector.dto.InstanceHierarchy.HierarchyNode;
import io.inspector.dto.InstanceJobs;
import io.inspector.dto.InstanceJobs.JobDto;
import io.inspector.dto.InstanceStatusFlags;
import io.inspector.dto.InstanceTasks;
import io.inspector.dto.InstanceTasks.TaskDto;
import io.inspector.dto.InstanceTimeline;
import io.inspector.dto.InstanceTimeline.LiveJobState;
import io.inspector.dto.InstanceTimeline.TimelineActivity;
import io.inspector.dto.InstanceVariables;
import io.inspector.dto.InstanceVariables.ExecutionScope;
import io.inspector.dto.InstanceVariables.VariableDto;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineRegistry;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The Stage 2 detail aggregator (SPEC §4, ARCH §4): per-instance reads composed from the
 * whitelisted engine calls. Doctrine held throughout:
 *
 * <ul>
 *   <li><b>Historic-first</b> — every endpoint starts from the historic row, so a COMPLETED
 *       instance renders instead of 404ing (flowable-rest skill §2); runtime enrichment
 *       (suspended state, jobs, executions) applies only while the instance is open.</li>
 *   <li><b>Typed variables</b> — the ledger keeps the engine-declared type next to a
 *       byte-capped typed value (R-UXQ-13); a structured value over
 *       {@value #STRUCTURED_PREVIEW_CAP_BYTES} bytes ships truncated with an on-demand
 *       full fetch. Never a blind JSON map.</li>
 *   <li><b>Bounded trees</b> — the hierarchy walk is depth-capped (registry), breadth-capped
 *       at {@value #HIERARCHY_BREADTH_CAP}/node (R-SEM-19) with exact count-only totals
 *       beyond the render cap, and cycle-guarded.</li>
 * </ul>
 */
@Service
public class InstanceDetailService {

    private static final Logger log = LoggerFactory.getLogger(InstanceDetailService.class);

    /** SPEC §4 size safeguard: structured previews cap at 256 KiB serialized. */
    public static final int STRUCTURED_PREVIEW_CAP_BYTES = 256 * 1024;

    /** R-SEM-19: max children RENDERED per node; totals stay exact via the query total. */
    static final int HIERARCHY_BREADTH_CAP = 50;

    /** Backstop over depth×breadth so a pathological tree cannot flood the BFF or the UI. */
    static final int HIERARCHY_MAX_RENDERED_NODES = 500;

    private static final ObjectMapper SIZER = new ObjectMapper();

    private final EngineRegistry registry;
    private final ProcessApiClient flowable;
    private final ExternalJobApiClient externalJobs;
    private final InspectorProperties props;
    private final ProtectedInstanceRepository protectedInstances;

    public InstanceDetailService(
            EngineRegistry registry,
            ProcessApiClient flowable,
            ExternalJobApiClient externalJobs,
            InspectorProperties props,
            ProtectedInstanceRepository protectedInstances) {
        this.registry = registry;
        this.flowable = flowable;
        this.externalJobs = externalJobs;
        this.props = props;
        this.protectedInstances = protectedInstances;
    }

    /* ================= vitals ================= */

    public InstanceDetail vitals(String engineId, String instanceId) {
        EngineConfig engine = registry.require(engineId);
        Map<String, Object> historic = requireHistoric(engine, instanceId);
        boolean ended = str(historic, "endTime") != null;

        Lanes lanes = ended ? Lanes.EMPTY : scanInstanceLanes(engine, instanceId);
        InstanceStatusFlags flags = flagsFor(engine, historic, lanes);

        List<CurrentActivity> current = ended ? List.of() : currentActivities(engine, instanceId);
        WhyStuck whyStuck = whyStuck(engine, lanes);
        List<WaitState> waitingFor = ended ? List.of() : waitingFor(engine, instanceId, lanes);
        Integer externalWorkerJobs = ended ? null : externalWorkerCount(engine, instanceId);

        String definitionId = str(historic, "processDefinitionId");
        Object duration = historic.get("durationInMillis");
        Protection protection = protectionOf(engine.id(), instanceId);
        return new InstanceDetail(
                engine.id() + ":" + instanceId,
                engine.id(),
                instanceId,
                str(historic, "businessKey"),
                str(historic, "tenantId"),
                definitionId,
                definitionKeyOf(definitionId),
                str(historic, "processDefinitionName"),
                definitionVersionOf(definitionId),
                str(historic, "startTime"),
                str(historic, "endTime"),
                duration instanceof Number n ? n.longValue() : null,
                str(historic, "startUserId"),
                flags,
                flags.primaryStatus(),
                str(historic, "superProcessInstanceId"),
                renderTelemetryUrl(
                        engine.telemetryUrlTemplate(),
                        instanceId,
                        str(historic, "businessKey"),
                        whyStuck != null ? whyStuck.failureTime() : null),
                current,
                whyStuck,
                waitingFor,
                externalWorkerJobs,
                protection.isProtected(),
                protection.reason(),
                terminationReason(historic, ended));
    }

    /**
     * Status honesty (#118/#105): distinguish a TERMINATED/deleted ended instance from a genuine
     * completion. Flowable ends both with an {@code endTime}, so {@code primaryStatus()} maps both
     * to COMPLETED — but a historic instance also carries a {@code state} (6.x+:
     * {@code COMPLETED}/{@code EXTERNALLY_TERMINATED}/{@code INTERNALLY_TERMINATED}/{@code DELETED})
     * and/or a {@code deleteReason}. Returns the termination reason (deleteReason preferred, else a
     * humanized state) for a terminated instance, or {@code null} for a normal completion / while
     * running. No engine-client change — the historic Map already carries these keys.
     */
    static String terminationReason(Map<String, Object> historic, boolean ended) {
        if (!ended) {
            return null;
        }
        String state = str(historic, "state");
        String deleteReason = str(historic, "deleteReason");
        boolean hasDeleteReason = deleteReason != null && !deleteReason.isBlank();
        if (state != null && !state.isBlank()) {
            if ("COMPLETED".equalsIgnoreCase(state)) {
                return null; // a normal completion — no badge
            }
            return hasDeleteReason
                    ? deleteReason
                    : state.toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        // Pre-6.x engines don't serialize `state`; a deleteReason on an ended instance = terminated.
        return hasDeleteReason ? deleteReason : null;
    }

    /** The vitals protected-state read + its reason; {@code isProtected==null} = store unreachable. */
    private record Protection(Boolean isProtected, String reason) {}

    /**
     * R-SAFE-05 point-of-action read (usability W3 sliver): one batched-key lookup for THIS
     * instance so the vitals header can show the protected badge and the UI can grey every
     * verb below the ADMIN floor. A Postgres outage degrades to {@code isProtected=null}
     * (unknown) — vitals must never fail over it; the execution-time guard still refuses
     * fail-closed. Mirrors {@code SearchService.markProtected} on the grid path.
     */
    private Protection protectionOf(String engineId, String instanceId) {
        try {
            return protectedInstances
                    .findById(new ProtectedInstance.Key(engineId, instanceId))
                    .map(p -> new Protection(true, p.getReason()))
                    .orElseGet(() -> new Protection(false, null));
        } catch (RuntimeException e) {
            log.warn("protected-instance lookup unavailable — vitals carries protectedInstance=null: {}", e.toString());
            return new Protection(null, null);
        }
    }

    /**
     * The external-worker job count for the vitals diagnostic summary (v1.x #7). Null on a
     * pre-6.8 engine (the fifth queue does not apply) — never a misleading 0. An optional
     * count must never break the vitals render, so an engine hiccup degrades to null too.
     */
    private Integer externalWorkerCount(EngineConfig engine, String instanceId) {
        EngineCapabilities capabilities = registry.healthOf(engine.id()).capabilities();
        if (capabilities == null || !capabilities.externalWorkerJobs()) {
            return null;
        }
        try {
            return (int) externalJobs
                    .listExternalWorkerJobs(
                            engine, CallPriority.INTERACTIVE, Map.of("processInstanceId", instanceId), 0, 1)
                    .total();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The "open logs" deep link (SPEC §4): the engine's OPTIONAL telemetry URL template
     * with placeholder VALUES url-encoded in place. Absent template → null → no link,
     * never a broken guess. {@code {executionId}} renders empty at instance level — most
     * APM queries treat an empty term as a no-op filter.
     */
    static String renderTelemetryUrl(
            String template, String processInstanceId, String businessKey, String failureTime) {
        if (template == null || template.isBlank()) return null;
        return template.replace("{processInstanceId}", encodeValue(processInstanceId))
                .replace("{executionId}", "")
                .replace("{businessKey}", encodeValue(businessKey))
                .replace("{failureTime}", encodeValue(failureTime));
    }

    private static String encodeValue(String value) {
        return value == null ? "" : java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /* ================= diagram ================= */

    public InstanceDiagram diagram(String engineId, String instanceId) {
        EngineConfig engine = registry.require(engineId);
        Map<String, Object> historic = requireHistoric(engine, instanceId);
        String definitionId = str(historic, "processDefinitionId");
        Map<String, Object> definition = flowable.getProcessDefinition(engine, CallPriority.INTERACTIVE, definitionId);
        if (definition == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "process definition " + definitionId + " not found on " + engineId);
        }
        String deploymentId = str(definition, "deploymentId");
        String resourceName = resourceNameOf(definition);
        String xml = deploymentId == null || resourceName == null
                ? null
                : flowable.deploymentResourceData(engine, CallPriority.INTERACTIVE, deploymentId, resourceName);
        if (xml == null || xml.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "BPMN resource for definition " + definitionId + " not found on " + engineId);
        }

        boolean ended = str(historic, "endTime") != null;
        List<String> active = ended
                ? List.of()
                : currentActivities(engine, instanceId).stream()
                        .map(CurrentActivity::activityId)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList();
        Lanes lanes = ended ? Lanes.EMPTY : scanInstanceLanes(engine, instanceId);
        List<String> deadLetterActivities = lanes.deadLetter().stream()
                .map(job -> failingActivityIdOf(engine, job))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        return new InstanceDiagram(xml, active, deadLetterActivities);
    }

    /**
     * The deployment resource name of a definition. 6.x serializes it only as the
     * {@code resource} URL ({@code …/deployments/{id}/resources/{name}}) — the name is the
     * decoded tail. A plain {@code resourceName} field (newer engines) wins when present.
     */
    static String resourceNameOf(Map<String, Object> definition) {
        String plain = str(definition, "resourceName");
        if (plain != null && !plain.isBlank()) return plain;
        String url = str(definition, "resource");
        if (url == null) return null;
        int at = url.indexOf("/resources/");
        if (at < 0) return null;
        return URLDecoder.decode(url.substring(at + "/resources/".length()), StandardCharsets.UTF_8);
    }

    /* ================= variables — the typed ledger ================= */

    public InstanceVariables variables(String engineId, String instanceId) {
        EngineConfig engine = registry.require(engineId);
        Map<String, Object> historic = requireHistoric(engine, instanceId);
        if (str(historic, "endTime") != null) {
            return historicVariables(engine, instanceId);
        }

        List<Map<String, Object>> processRows =
                flowable.listInstanceVariables(engine, CallPriority.INTERACTIVE, instanceId);
        if (processRows == null) {
            // Ended between the historic read and now — serve the historic projection.
            return historicVariables(engine, instanceId);
        }
        List<VariableDto> processScope = processRows.stream()
                .map(row -> typedRow(row, str(row, "scope"), null, null))
                .sorted(Comparator.comparing(VariableDto::name, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        // Execution-local segregation (SPEC §4): every non-root execution may hold locals —
        // multi-instance loop variables live there. Empty scopes are dropped, not rendered.
        List<ExecutionScope> executionScopes = new ArrayList<>();
        for (Map<String, Object> execution : flowable.listExecutions(
                        engine, CallPriority.INTERACTIVE, instanceId, engine.maxPageSizeOrDefault())
                .dataOrEmpty()) {
            String executionId = str(execution, "id");
            if (executionId == null || executionId.equals(instanceId)) continue; // root = process scope
            List<VariableDto> locals =
                    flowable.listExecutionLocalVariables(engine, CallPriority.INTERACTIVE, executionId).stream()
                            .map(row -> typedRow(row, "local", executionId, null))
                            .sorted(Comparator.comparing(
                                    VariableDto::name, Comparator.nullsLast(Comparator.naturalOrder())))
                            .toList();
            if (!locals.isEmpty()) {
                executionScopes.add(new ExecutionScope(
                        executionId, str(execution, "activityId"), str(execution, "parentId"), locals));
            }
        }
        return new InstanceVariables("RUNTIME", processScope, executionScopes);
    }

    /**
     * On-demand full value (SPEC §4a: an edit always operates on the fetched full value).
     * Scope-aware: a blank {@code executionId} reads the process (case) scope; a non-blank one
     * reads the execution-local ("step-local") variable {@code scope=local} on that node — the
     * base value the step-local editor stages and CASes against.
     */
    public VariableDto variable(String engineId, String instanceId, String name, String executionId) {
        EngineConfig engine = registry.require(engineId);
        requireHistoric(engine, instanceId);
        boolean local = executionId != null && !executionId.isBlank();
        Map<String, Object> row = local
                ? flowable.getExecutionVariable(engine, CallPriority.INTERACTIVE, executionId, name)
                : flowable.getInstanceVariable(engine, CallPriority.INTERACTIVE, instanceId, name);
        if (row == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    local
                            ? "step-local variable '" + name + "' not found on execution " + executionId
                            : "variable '" + name + "' not found on running instance " + engineId + ":" + instanceId);
        }
        // The full fetch is deliberately uncapped — this IS the "load full value" escape
        // hatch; the write cap (5 MiB, SPEC §4a) guards the other direction.
        return new VariableDto(
                str(row, "name"),
                str(row, "type"),
                row.get("value"),
                false,
                null,
                local ? "local" : str(row, "scope"),
                local ? executionId : null,
                null);
    }

    private InstanceVariables historicVariables(EngineConfig engine, String instanceId) {
        List<VariableDto> rows = new ArrayList<>();
        for (Map<String, Object> row : flowable.listHistoricVariableInstances(
                        engine, CallPriority.INTERACTIVE, instanceId, engine.maxPageSizeOrDefault())
                .dataOrEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> variable = row.get("variable") instanceof Map<?, ?> v ? (Map<String, Object>) v : row;
            rows.add(typedRow(variable, "global", str(row, "executionId"), str(row, "taskId")));
        }
        rows.sort(Comparator.comparing(VariableDto::name, Comparator.nullsLast(Comparator.naturalOrder())));
        return new InstanceVariables("HISTORIC", rows, List.of());
    }

    /**
     * One typed ledger row (R-UXQ-13): engine type verbatim, TYPED value preserved — a
     * number stays a JSON number — capped at {@value #STRUCTURED_PREVIEW_CAP_BYTES} bytes
     * for structured/text payloads (SPEC §4 size safeguards).
     */
    public static VariableDto typedRow(Map<String, Object> row, String scope, String executionId, String taskId) {
        Object value = row.get("value");
        boolean truncated = false;
        Long sizeBytes = null;
        if (value instanceof String s) {
            long bytes = s.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > STRUCTURED_PREVIEW_CAP_BYTES) {
                truncated = true;
                sizeBytes = bytes;
                value = null;
            }
        } else if (value instanceof Map || value instanceof List) {
            long bytes = serializedSize(value);
            sizeBytes = bytes;
            if (bytes > STRUCTURED_PREVIEW_CAP_BYTES) {
                truncated = true;
                value = null;
            }
        }
        return new VariableDto(
                str(row, "name"), str(row, "type"), value, truncated, sizeBytes, scope, executionId, taskId);
    }

    private static long serializedSize(Object value) {
        try {
            return SIZER.writeValueAsBytes(value).length;
        } catch (JsonProcessingException e) {
            return Long.MAX_VALUE; // unserializable = over any cap; the row ships truncated
        }
    }

    /* ================= jobs — four lanes, kept distinct ================= */

    public InstanceJobs jobs(String engineId, String instanceId) {
        EngineConfig engine = registry.require(engineId);
        requireHistoric(engine, instanceId);
        return new InstanceJobs(
                laneRows(engine, instanceId, JobLaneKind.EXECUTABLE),
                laneRows(engine, instanceId, JobLaneKind.TIMER),
                laneRows(engine, instanceId, JobLaneKind.SUSPENDED),
                laneRows(engine, instanceId, JobLaneKind.DEADLETTER));
    }

    /** Plain-text stacktrace, fetched on expand (SPEC §4) — 404 when the job moved on. */
    public String jobStacktrace(String engineId, String instanceId, String jobId, JobLaneKind lane) {
        EngineConfig engine = registry.require(engineId);
        requireHistoric(engine, instanceId);
        String stacktrace = flowable.jobExceptionStacktrace(engine, CallPriority.INTERACTIVE, lane, jobId);
        if (stacktrace == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "no stacktrace for job " + jobId + " in the " + lane + " lane (retried or deleted?)");
        }
        return stacktrace;
    }

    private List<JobDto> laneRows(EngineConfig engine, String instanceId, JobLaneKind lane) {
        return instanceJobs(engine, instanceId, lane).stream()
                .map(job -> jobRow(job, lane))
                .toList();
    }

    static JobDto jobRow(Map<String, Object> job, JobLaneKind lane) {
        Object retries = job.get("retries");
        return new JobDto(
                str(job, "id"),
                lane.name(),
                str(job, "createTime"),
                str(job, "dueDate"),
                retries instanceof Number n ? n.intValue() : null,
                str(job, "exceptionMessage"),
                str(job, "elementId"),
                str(job, "elementName"),
                str(job, "executionId"),
                str(job, "processDefinitionId"),
                str(job, "tenantId"));
    }

    /* ============ external-worker jobs — the fifth queue (v1.x #7) ============ */

    /**
     * External-worker jobs for the instance (read-only). Capability-gated (Flowable ≥ 6.8):
     * on an older engine the BFF refuses with a ProblemDetail rather than letting the call die
     * as a confusing 404 at the sibling external-job-api context. The UI never reaches here on
     * a pre-6.8 engine — it reads the same flag off {@code EngineDto.capabilities} — but the
     * server stays the gate.
     */
    public List<ExternalWorkerJobDto> externalWorkerJobs(String engineId, String instanceId) {
        EngineConfig engine = registry.require(engineId);
        requireExternalWorkerCapability(engine);
        requireHistoric(engine, instanceId);
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("processInstanceId", instanceId);
        if (engine.tenantId() != null && !engine.tenantId().isBlank()) {
            filters.put("tenantId", engine.tenantId());
        }
        return externalJobs
                .listExternalWorkerJobs(engine, CallPriority.INTERACTIVE, filters, 0, engine.maxPageSizeOrDefault())
                .dataOrEmpty()
                .stream()
                .map(InstanceDetailService::externalWorkerRow)
                .toList();
    }

    private void requireExternalWorkerCapability(EngineConfig engine) {
        EngineCapabilities capabilities = registry.healthOf(engine.id()).capabilities();
        if (capabilities == null) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "capability-unknown",
                    "Engine '" + engine.id() + "' has not answered a health probe yet — external-worker support"
                            + " (Flowable ≥ 6.8) is unverified, so the call is refused rather than sent blind.");
        }
        if (!capabilities.externalWorkerJobs()) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "capability-unavailable",
                    "Engine '" + engine.id() + "' runs an Unsupported Engine Version for external-worker jobs"
                            + " (requires Flowable ≥ 6.8) — refused in the BFF, never a confusing engine 404.");
        }
    }

    static ExternalWorkerJobDto externalWorkerRow(Map<String, Object> job) {
        Object retries = job.get("retries");
        return new ExternalWorkerJobDto(
                str(job, "id"),
                str(job, "elementId"),
                str(job, "elementName"),
                str(job, "lockOwner"),
                str(job, "lockExpirationTime"),
                retries instanceof Number n ? n.intValue() : null,
                str(job, "exceptionMessage"),
                str(job, "createTime"),
                str(job, "dueDate"),
                str(job, "executionId"),
                str(job, "processDefinitionId"),
                str(job, "tenantId"));
    }

    /* ================= tasks — historic ∪ runtime ================= */

    /**
     * The instance's user tasks, completed AND open (SPEC §4). Historic-first: the
     * historic-task table carries open tasks as rows with a null endTime, so one leg
     * already covers the whole lifecycle. The runtime leg then enriches open rows with
     * live-only state (suspension) and adds any task the history level hides — same
     * union doctrine as {@link #currentActivities}.
     */
    public InstanceTasks tasks(String engineId, String instanceId) {
        EngineConfig engine = registry.require(engineId);
        Map<String, Object> historic = requireHistoric(engine, instanceId);

        Map<String, Map<String, Object>> runtimeById = new LinkedHashMap<>();
        if (str(historic, "endTime") == null) {
            for (Map<String, Object> task : flowable.listRuntimeTasks(
                            engine, CallPriority.INTERACTIVE, instanceId, engine.maxPageSizeOrDefault())
                    .dataOrEmpty()) {
                String id = str(task, "id");
                if (id != null) runtimeById.put(id, task);
            }
        }

        long historicTotal = 0;
        Map<String, TaskDto> rows = new LinkedHashMap<>();
        try {
            FlowablePage page = flowable.listHistoricTaskInstances(
                    engine, CallPriority.INTERACTIVE, instanceId, engine.maxPageSizeOrDefault());
            historicTotal = page.total();
            for (Map<String, Object> task : page.dataOrEmpty()) {
                String id = str(task, "id");
                if (id == null) continue;
                rows.put(id, taskRow(task, runtimeById.get(id)));
            }
        } catch (Exception ex) {
            // Task history below audit level (legal engine config) — the runtime leg
            // still renders the open tasks; degrade, never 500.
            log.debug("Historic-task leg unavailable on {}: {}", engine.id(), ex.toString());
        }
        for (Map.Entry<String, Map<String, Object>> entry : runtimeById.entrySet()) {
            rows.computeIfAbsent(entry.getKey(), id -> taskRow(null, entry.getValue()));
        }

        List<TaskDto> tasks = rows.values().stream()
                .sorted(Comparator.comparing(TaskDto::createTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        long total = Math.max(historicTotal, tasks.size());
        return new InstanceTasks(tasks, total, total > tasks.size());
    }

    /**
     * One task row from whichever legs answered. Field names differ per leg — historic
     * rows say {@code startTime}, runtime rows say {@code createTime}; only the runtime
     * row knows {@code suspended}. COMPLETED beats SUSPENDED beats ACTIVE.
     */
    static TaskDto taskRow(Map<String, Object> historic, Map<String, Object> runtime) {
        Map<String, Object> h = historic != null ? historic : Map.of();
        Map<String, Object> r = runtime != null ? runtime : Map.of();
        String endTime = str(h, "endTime");
        String state = endTime != null
                ? TaskDto.STATE_COMPLETED
                : Boolean.TRUE.equals(r.get("suspended")) ? TaskDto.STATE_SUSPENDED : TaskDto.STATE_ACTIVE;
        Object duration = h.get("durationInMillis");
        return new TaskDto(
                first(str(h, "id"), str(r, "id")),
                first(str(h, "name"), str(r, "name")),
                first(str(h, "taskDefinitionKey"), str(r, "taskDefinitionKey")),
                first(str(r, "assignee"), str(h, "assignee")),
                first(str(r, "owner"), str(h, "owner")),
                first(str(h, "startTime"), str(r, "createTime")),
                endTime,
                first(str(h, "dueDate"), str(r, "dueDate")),
                duration instanceof Number n ? n.longValue() : null,
                state);
    }

    private static String first(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }

    /* ================= hierarchy — bounded, both directions ================= */

    public InstanceHierarchy hierarchy(String engineId, String instanceId) {
        EngineConfig engine = registry.require(engineId);
        Map<String, Object> requested = requireHistoric(engine, instanceId);
        int maxDepth = props.hierarchyMaxDepthOrDefault();

        // UP: walk the superProcessInstanceId chain to the root — one historic GET per
        // ancestor, cycle-guarded, depth-capped (a real engine cannot produce a cycle;
        // the guard is doctrine, R-TEST-07).
        Map<String, Object> root = requested;
        Set<String> visited = new HashSet<>();
        visited.add(instanceId);
        for (int depth = 0; depth < maxDepth; depth++) {
            String parentId = str(root, "superProcessInstanceId");
            if (parentId == null || !visited.add(parentId)) break;
            Map<String, Object> parent =
                    flowable.getHistoricProcessInstance(engine, CallPriority.INTERACTIVE, parentId);
            if (parent == null) break; // parent purged from history — current node is the root
            root = parent;
        }
        String rootId = str(root, "id");

        // DOWN: BFS from the root. Children come from ONE historic query per node
        // (superProcessInstanceId filter), rendered up to the breadth cap; the query total
        // keeps counts exact beyond it (count-only doctrine, R-SEM-19).
        Map<String, NodeBuild> builds = new LinkedHashMap<>();
        NodeBuild rootBuild = new NodeBuild(root, 0);
        builds.put(rootId, rootBuild);
        Deque<NodeBuild> queue = new ArrayDeque<>();
        queue.add(rootBuild);
        boolean depthLimitReached = false;
        while (!queue.isEmpty()) {
            NodeBuild node = queue.poll();
            FlowablePage page = childrenOf(engine, node.id());
            node.childTotal = page.total();
            if (node.depth >= maxDepth) {
                depthLimitReached |= page.total() > 0;
                continue;
            }
            for (Map<String, Object> child : page.dataOrEmpty()) {
                if (node.children.size() >= HIERARCHY_BREADTH_CAP || builds.size() >= HIERARCHY_MAX_RENDERED_NODES) {
                    break;
                }
                String childId = str(child, "id");
                if (childId == null || builds.containsKey(childId)) continue; // cycle guard
                NodeBuild childBuild = new NodeBuild(child, node.depth + 1);
                builds.put(childId, childBuild);
                node.children.add(childBuild);
                queue.add(childBuild);
            }
        }

        // Child failures surface per node (SPEC §4) — count-only DLQ membership per
        // rendered node, bounded by the render caps above.
        for (NodeBuild node : builds.values()) {
            node.hasDeadLetterJobs = deadLetterCount(engine, node.id()) > 0;
        }
        return new InstanceHierarchy(
                instanceId, rootId, rootBuild.toDto(instanceId), depthLimitReached, maxDepth, HIERARCHY_BREADTH_CAP);
    }

    private FlowablePage childrenOf(EngineConfig engine, String parentInstanceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("superProcessInstanceId", parentInstanceId);
        body.put("size", Math.min(HIERARCHY_BREADTH_CAP, engine.maxPageSizeOrDefault()));
        withTenant(engine, body);
        return flowable.queryHistoricProcessInstances(engine, CallPriority.INTERACTIVE, body);
    }

    /** Mutable BFS scaffold; the immutable DTO is assembled bottom-up at the end. */
    private static final class NodeBuild {
        final Map<String, Object> row;
        final int depth;
        final List<NodeBuild> children = new ArrayList<>();
        long childTotal;
        boolean hasDeadLetterJobs;

        NodeBuild(Map<String, Object> row, int depth) {
            this.row = row;
            this.depth = depth;
        }

        String id() {
            return str(row, "id");
        }

        HierarchyNode toDto(String requestedId) {
            String definitionId = str(row, "processDefinitionId");
            return new HierarchyNode(
                    id(),
                    str(row, "businessKey"),
                    definitionKeyOf(definitionId),
                    str(row, "processDefinitionName"),
                    definitionVersionOf(definitionId),
                    str(row, "startTime"),
                    str(row, "endTime"),
                    str(row, "endTime") != null,
                    hasDeadLetterJobs,
                    id().equals(requestedId),
                    childTotal,
                    childTotal > children.size(),
                    children.stream().map(c -> c.toDto(requestedId)).toList());
        }
    }

    /* ================= timeline ================= */

    public InstanceTimeline timeline(String engineId, String instanceId) {
        EngineConfig engine = registry.require(engineId);
        requireHistoric(engine, instanceId);
        int maxDepth = props.hierarchyMaxDepthOrDefault();
        // The cycle guard (R-TEST-07 doctrine): a real engine cannot produce a
        // calledProcessInstanceId cycle, but the visited set makes the recursion total anyway.
        Set<String> visited = new HashSet<>();
        visited.add(instanceId);
        // Global backstop over depth×breadth so a parallel-MI fan-out cannot flood the BFF.
        int[] budget = {HIERARCHY_MAX_RENDERED_NODES};
        Level root = timelineLevel(engine, instanceId, 0, maxDepth, visited, budget, engine.maxPageSizeOrDefault());
        return new InstanceTimeline(root.activities(), root.total(), root.truncated());
    }

    /** The rows of ONE instance plus its own page total/truncation (fed to the parent's cap flag). */
    private record Level(List<TimelineActivity> activities, long total, boolean truncated) {}

    /**
     * Builds one instance's activity rows, recursively nesting each call-activity's called
     * instance as a sub-lane. Recursion is bounded three ways — depth ({@code maxDepth}),
     * breadth ({@code pageSize}, the hierarchy cap for children) and a global node
     * {@code budget} — and cycle-guarded on {@code visited} process-instance ids. Failing
     * nodes carry a live job state; a dead-lettered async node, whose history row rolled back
     * with its transaction, is synthesized from the runtime lanes (the phantom-node union).
     */
    private Level timelineLevel(
            EngineConfig engine,
            String instanceId,
            int depth,
            int maxDepth,
            Set<String> visited,
            int[] budget,
            int pageSize) {
        FlowablePage page = flowable.listHistoricActivities(engine, CallPriority.INTERACTIVE, instanceId, 0, pageSize);
        List<Map<String, Object>> rows = page.dataOrEmpty();
        boolean truncated = page.total() > rows.size();

        // Live failure is read from the runtime lanes (the source of truth), NOT from the
        // historic rows — an async dead-letter leaves no ACT_HI_ACTINST row at all.
        Map<String, LiveJobState> stateByActivity = liveJobStates(engine, instanceId);
        Set<String> annotated = new HashSet<>();

        List<TimelineActivity> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String activityId = str(row, "activityId");
            LiveJobState state = null;
            if (activityId != null && str(row, "endTime") == null && stateByActivity.containsKey(activityId)) {
                state = stateByActivity.get(activityId);
                annotated.add(activityId);
            }

            List<TimelineActivity> children = List.of();
            boolean capped = false;
            String childInstanceId = str(row, "calledProcessInstanceId");
            if (childInstanceId != null) {
                if (depth >= maxDepth || budget[0] <= 0) {
                    capped = true; // depth or node-budget cap — the drill-out link still stands
                } else if (visited.add(childInstanceId)) {
                    budget[0]--;
                    Level child = timelineLevel(
                            engine, childInstanceId, depth + 1, maxDepth, visited, budget, HIERARCHY_BREADTH_CAP);
                    children = child.activities();
                    capped = child.truncated(); // the child's own page was breadth-truncated
                }
                // else: already visited — cycle guard; leave the sub-lane unexpanded, link stands.
            }
            out.add(withSubLane(timelineRow(row), state, capped, children));
        }

        // Phantom-node union: any live failure whose activity produced no annotated row is a
        // rolled-back async dead-letter — surface it so the failure is never invisible.
        for (Map.Entry<String, LiveJobState> entry : stateByActivity.entrySet()) {
            if (annotated.contains(entry.getKey())) continue;
            out.add(phantomNode(entry.getKey(), entry.getValue(), rows));
        }
        return new Level(out, page.total(), truncated);
    }

    /** activityId → live job state for one instance: dead-letter (FAILED) wins over failing (RETRYING). */
    private Map<String, LiveJobState> liveJobStates(EngineConfig engine, String instanceId) {
        Lanes lanes = scanInstanceLanes(engine, instanceId);
        if (lanes.deadLetter().isEmpty() && lanes.failing().isEmpty()) return Map.of();
        Map<String, LiveJobState> byActivity = new LinkedHashMap<>();
        for (Map<String, Object> job : lanes.failing()) {
            String activityId = failingActivityIdOf(engine, job);
            if (activityId != null) byActivity.putIfAbsent(activityId, LiveJobState.RETRYING);
        }
        for (Map<String, Object> job : lanes.deadLetter()) {
            String activityId = failingActivityIdOf(engine, job);
            if (activityId != null) byActivity.put(activityId, LiveJobState.FAILED); // override RETRYING
        }
        return byActivity;
    }

    /**
     * A synthesized bar for a failing activity with no historic row (async rollback). Borrows
     * the name/type from any sibling row sharing the activityId (a prior finished attempt);
     * otherwise the activityId stands alone. Unfinished by construction — it carries no time.
     */
    private static TimelineActivity phantomNode(String activityId, LiveJobState state, List<Map<String, Object>> rows) {
        String name = null;
        String type = null;
        for (Map<String, Object> row : rows) {
            if (activityId.equals(str(row, "activityId"))) {
                name = str(row, "activityName");
                type = str(row, "activityType");
                break;
            }
        }
        return new TimelineActivity(
                null, activityId, name, type, null, null, null, null, null, null, null, state, false, List.of());
    }

    private static TimelineActivity withSubLane(
            TimelineActivity base, LiveJobState state, boolean capped, List<TimelineActivity> children) {
        return new TimelineActivity(
                base.id(),
                base.activityId(),
                base.activityName(),
                base.activityType(),
                base.executionId(),
                base.startTime(),
                base.endTime(),
                base.durationMs(),
                base.assignee(),
                base.taskId(),
                base.calledProcessInstanceId(),
                state,
                capped,
                children);
    }

    static TimelineActivity timelineRow(Map<String, Object> row) {
        Object duration = row.get("durationInMillis");
        return new TimelineActivity(
                str(row, "id"),
                str(row, "activityId"),
                str(row, "activityName"),
                str(row, "activityType"),
                str(row, "executionId"),
                str(row, "startTime"),
                str(row, "endTime"),
                duration instanceof Number n ? n.longValue() : null,
                str(row, "assignee"),
                str(row, "taskId"),
                str(row, "calledProcessInstanceId"),
                null,
                false,
                List.of());
    }

    /* ================= shared: flags for resolve + vitals ================= */

    /**
     * Per-instance status flags for a SINGLE instance (resolve matches, vitals) — the
     * same semantics as the search join, derived from per-instance count queries instead
     * of scans: ended from the historic row, suspended from the runtime row, dead-letter/
     * failing from count-only lane queries, failedInSubprocess from a bounded child walk
     * with early exit.
     */
    public InstanceStatusFlags flagsFor(EngineConfig engine, Map<String, Object> historicRow) {
        return deriveStatus(engine, historicRow).flags();
    }

    /**
     * The single-instance status derivation WITH its provenance (R-L3-01, SPEC §3) — the same
     * flags {@link #vitals} and resolve render, plus the evidence trail behind each one: which
     * failure-lane jobs set {@code hasDeadLetterJobs}/{@code hasFailingJobs}, and which
     * call-activity descendant (and its dead-letter job) set {@code failedInSubprocess}. The
     * raw per-leg engine calls are captured separately by {@link EngineCallRecorder}; this
     * record supplies the SEMANTIC link (flag ⇐ job/child), so "Explain this status" can say
     * exactly why a chip is what it is — the fix for the retest status-contradiction (grid
     * parent ACTIVE vs detail FAILED "in subprocess").
     */
    public record StatusDerivation(
            InstanceStatusFlags flags,
            boolean ended,
            List<String> deadLetterJobIds,
            List<String> failingJobIds,
            String failingChildInstanceId,
            String failingChildJobId) {}

    public StatusDerivation deriveStatus(EngineConfig engine, Map<String, Object> historicRow) {
        String instanceId = str(historicRow, "id");
        boolean ended = str(historicRow, "endTime") != null;
        if (ended) {
            return new StatusDerivation(
                    new InstanceStatusFlags(true, false, false, false, false), true, List.of(), List.of(), null, null);
        }
        Lanes lanes = scanInstanceLanes(engine, instanceId);
        Map<String, Object> runtime = flowable.getRuntimeProcessInstance(engine, CallPriority.INTERACTIVE, instanceId);
        boolean suspended = runtime != null && Boolean.TRUE.equals(runtime.get("suspended"));
        FailedChild failedChild = firstFailedDescendant(engine, instanceId);
        InstanceStatusFlags flags = new InstanceStatusFlags(
                false,
                suspended,
                !lanes.deadLetter().isEmpty(),
                !lanes.failing().isEmpty(),
                failedChild != null);
        return new StatusDerivation(
                flags,
                false,
                jobIds(lanes.deadLetter()),
                jobIds(lanes.failing()),
                failedChild != null ? failedChild.childInstanceId() : null,
                failedChild != null ? failedChild.jobId() : null);
    }

    private InstanceStatusFlags flagsFor(EngineConfig engine, Map<String, Object> historicRow, Lanes lanes) {
        String instanceId = str(historicRow, "id");
        boolean ended = str(historicRow, "endTime") != null;
        if (ended) {
            return new InstanceStatusFlags(true, false, false, false, false);
        }
        Map<String, Object> runtime = flowable.getRuntimeProcessInstance(engine, CallPriority.INTERACTIVE, instanceId);
        boolean suspended = runtime != null && Boolean.TRUE.equals(runtime.get("suspended"));
        boolean hasDeadLetter = !lanes.deadLetter().isEmpty();
        boolean hasFailing = !lanes.failing().isEmpty();
        boolean failedInSubprocess = firstFailedDescendant(engine, instanceId) != null;
        return new InstanceStatusFlags(false, suspended, hasDeadLetter, hasFailing, failedInSubprocess);
    }

    private static List<String> jobIds(List<Map<String, Object>> jobs) {
        return jobs.stream()
                .map(job -> str(job, "id"))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** The first failed call-activity descendant found — its instance id + the dead-letter job id. */
    private record FailedChild(String childInstanceId, String jobId) {}

    /**
     * Bounded descendant walk with early exit: the FIRST call-activity child (transitively,
     * depth-capped) that holds a dead-letter job, or null when none does. One children query
     * per visited node; the offending child is confirmed with a {@code size=1} dead-letter
     * read that also yields the job id for the provenance trail (R-L3-01) — still one row,
     * never a scan.
     */
    private FailedChild firstFailedDescendant(EngineConfig engine, String instanceId) {
        Deque<String> frontier = new ArrayDeque<>();
        frontier.add(instanceId);
        Set<String> visited = new HashSet<>();
        visited.add(instanceId);
        int maxDepth = props.hierarchyMaxDepthOrDefault();
        for (int depth = 0; depth < maxDepth && !frontier.isEmpty(); depth++) {
            Deque<String> next = new ArrayDeque<>();
            while (!frontier.isEmpty()) {
                String parent = frontier.poll();
                for (Map<String, Object> child : childrenOf(engine, parent).dataOrEmpty()) {
                    String childId = str(child, "id");
                    if (childId == null || !visited.add(childId)) continue;
                    if (str(child, "endTime") == null) {
                        Map<String, Object> deadLetter = firstDeadLetterJob(engine, childId);
                        if (deadLetter != null) {
                            return new FailedChild(childId, str(deadLetter, "id"));
                        }
                    }
                    next.add(childId);
                }
            }
            frontier = next;
        }
        return null;
    }

    private Map<String, Object> firstDeadLetterJob(EngineConfig engine, String instanceId) {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("processInstanceId", instanceId);
        if (engine.tenantId() != null && !engine.tenantId().isBlank()) {
            filters.put("tenantId", engine.tenantId());
        }
        List<Map<String, Object>> rows = flowable.listJobs(
                        engine, CallPriority.INTERACTIVE, JobLaneKind.DEADLETTER, filters, 0, 1)
                .dataOrEmpty();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /* ================= per-instance lane reads ================= */

    /** The failure evidence of ONE instance: dead-letter rows + both withException lanes. */
    record Lanes(
            List<Map<String, Object>> deadLetter, List<Map<String, Object>> failing, List<Map<String, Object>> timers) {
        static final Lanes EMPTY = new Lanes(List.of(), List.of(), List.of());
    }

    private Lanes scanInstanceLanes(EngineConfig engine, String instanceId) {
        List<Map<String, Object>> deadLetter = instanceJobs(engine, instanceId, JobLaneKind.DEADLETTER);
        List<Map<String, Object>> timers = instanceJobs(engine, instanceId, JobLaneKind.TIMER);
        List<Map<String, Object>> failing = new ArrayList<>();
        for (Map<String, Object> job : instanceJobs(engine, instanceId, JobLaneKind.EXECUTABLE)) {
            if (str(job, "exceptionMessage") != null) failing.add(job);
        }
        for (Map<String, Object> job : timers) {
            if (str(job, "exceptionMessage") != null) failing.add(job);
        }
        return new Lanes(deadLetter, failing, timers);
    }

    private List<Map<String, Object>> instanceJobs(EngineConfig engine, String instanceId, JobLaneKind lane) {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("processInstanceId", instanceId);
        if (engine.tenantId() != null && !engine.tenantId().isBlank()) {
            filters.put("tenantId", engine.tenantId());
        }
        return flowable.listJobs(engine, CallPriority.INTERACTIVE, lane, filters, 0, engine.maxPageSizeOrDefault())
                .dataOrEmpty();
    }

    private long deadLetterCount(EngineConfig engine, String instanceId) {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("processInstanceId", instanceId);
        if (engine.tenantId() != null && !engine.tenantId().isBlank()) {
            filters.put("tenantId", engine.tenantId());
        }
        return flowable.listJobs(engine, CallPriority.INTERACTIVE, JobLaneKind.DEADLETTER, filters, 0, 1)
                .total();
    }

    /* ================= vitals sub-assemblies ================= */

    /**
     * Where the tokens are: unfinished historic activities UNIONED with the runtime
     * execution positions. Both legs are needed — a dead-lettered ASYNC task has NO
     * unfinished activity row (the failed transaction rolled it back; proven live on 6.8),
     * but its execution still sits at the activity; conversely the historic rows carry
     * names/types/start times the execution tree lacks.
     */
    private List<CurrentActivity> currentActivities(EngineConfig engine, String instanceId) {
        Map<String, CurrentActivity> byActivityId = new LinkedHashMap<>();
        try {
            for (Map<String, Object> row : flowable.listUnfinishedActivities(
                            engine,
                            CallPriority.INTERACTIVE,
                            instanceId,
                            engine.tenantId(),
                            engine.maxPageSizeOrDefault())
                    .dataOrEmpty()) {
                String activityId = str(row, "activityId");
                if (activityId == null) continue;
                byActivityId.putIfAbsent(
                        activityId,
                        new CurrentActivity(
                                activityId, str(row, "activityName"), str(row, "activityType"), str(row, "startTime")));
            }
        } catch (Exception ex) {
            // History-level below activity (legal engine config) — degrade, never 500.
            log.debug("Unfinished-activity leg unavailable on {}: {}", engine.id(), ex.toString());
        }
        try {
            for (Map<String, Object> execution : flowable.listExecutions(
                            engine, CallPriority.INTERACTIVE, instanceId, engine.maxPageSizeOrDefault())
                    .dataOrEmpty()) {
                String activityId = str(execution, "activityId");
                if (activityId == null) continue; // the root execution carries no position
                byActivityId.putIfAbsent(activityId, new CurrentActivity(activityId, null, null, null));
            }
        } catch (Exception ex) {
            log.debug("Execution-position leg unavailable on {}: {}", engine.id(), ex.toString());
        }
        return List.copyOf(byActivityId.values());
    }

    /** SPEC §4: exception first line, failing activity, retries state — null when healthy. */
    private WhyStuck whyStuck(EngineConfig engine, Lanes lanes) {
        if (lanes.deadLetter().isEmpty() && lanes.failing().isEmpty()) return null;
        Map<String, Object> newestDead = newestByCreateTime(lanes.deadLetter());
        Map<String, Object> newestFailing = newestByCreateTime(lanes.failing());
        Map<String, Object> evidence = newestDead != null ? newestDead : newestFailing;

        String exceptionMessage = str(evidence, "exceptionMessage");
        Object retries = newestFailing != null ? newestFailing.get("retries") : null;
        return new WhyStuck(
                exceptionMessage == null
                        ? null
                        : exceptionMessage.lines().findFirst().orElse(exceptionMessage),
                failingActivityIdOf(engine, evidence),
                str(evidence, "createTime"),
                lanes.deadLetter().size(),
                lanes.failing().size(),
                retries instanceof Number n ? n.intValue() : null,
                newestFailing != null ? str(newestFailing, "dueDate") : null);
    }

    /**
     * The activity a job is stuck on: {@code elementId} where the engine serializes it
     * (6.8+); a 6.3-era row falls back to ONE execution lookup — best-effort, a miss just
     * means no marker (never a failed vitals).
     */
    private String failingActivityIdOf(EngineConfig engine, Map<String, Object> job) {
        String elementId = str(job, "elementId");
        if (elementId != null) return elementId;
        String executionId = str(job, "executionId");
        if (executionId == null) return null;
        try {
            Map<String, Object> execution = flowable.getExecution(engine, CallPriority.INTERACTIVE, executionId);
            return execution != null ? str(execution, "activityId") : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private List<WaitState> waitingFor(EngineConfig engine, String instanceId, Lanes lanes) {
        List<WaitState> waits = new ArrayList<>();
        try {
            for (Map<String, Object> sub : flowable.listEventSubscriptions(
                            engine, CallPriority.INTERACTIVE, instanceId, engine.maxPageSizeOrDefault())
                    .dataOrEmpty()) {
                String eventType = str(sub, "eventType");
                waits.add(new WaitState(
                        eventType != null ? eventType.toUpperCase(java.util.Locale.ROOT) : "MESSAGE",
                        str(sub, "eventName"),
                        str(sub, "activityId"),
                        null,
                        str(sub, "created")));
            }
        } catch (Exception ex) {
            // The subscription endpoint is version-dependent — degrade, never fail vitals.
            log.debug("Event-subscription leg unavailable on {}: {}", engine.id(), ex.toString());
        }
        for (Map<String, Object> timer : lanes.timers()) {
            if (str(timer, "exceptionMessage") != null) continue; // that's a parked retry, not a wait
            waits.add(new WaitState(
                    "TIMER",
                    null,
                    failingActivityIdOf(engine, timer),
                    str(timer, "dueDate"),
                    str(timer, "createTime")));
        }
        return waits;
    }

    private static Map<String, Object> newestByCreateTime(List<Map<String, Object>> jobs) {
        return jobs.stream()
                .max(Comparator.comparing(
                        job -> str(job, "createTime"), Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    /* ================= shared plumbing ================= */

    /** The historic anchor row — exists for running AND completed instances. 404 when unknown. */
    public Map<String, Object> requireHistoric(EngineConfig engine, String instanceId) {
        Map<String, Object> historic =
                flowable.getHistoricProcessInstance(engine, CallPriority.INTERACTIVE, instanceId);
        if (historic == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "process instance " + instanceId + " not found on engine " + engine.id());
        }
        return historic;
    }

    private static void withTenant(EngineConfig engine, Map<String, Object> body) {
        if (engine.tenantId() != null && !engine.tenantId().isBlank()) {
            body.put("tenantId", engine.tenantId());
        }
    }

    static String definitionKeyOf(String definitionId) {
        if (definitionId == null) return null;
        int i = definitionId.indexOf(':');
        return i > 0 ? definitionId.substring(0, i) : definitionId;
    }

    static Integer definitionVersionOf(String definitionId) {
        if (definitionId == null) return null;
        String[] parts = definitionId.split(":", 3);
        try {
            return parts.length >= 2 ? Integer.valueOf(parts[1]) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
