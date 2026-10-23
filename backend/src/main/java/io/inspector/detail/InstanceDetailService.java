package io.inspector.detail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.ProtectedInstance;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.ExternalJobApiClient;
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
import io.inspector.dto.InstanceJobs;
import io.inspector.dto.InstanceJobs.JobDto;
import io.inspector.dto.InstanceStatusFlags;
import io.inspector.dto.InstanceTasks;
import io.inspector.dto.InstanceTasks.TaskDto;
import io.inspector.dto.InstanceTimeline;
import io.inspector.dto.InstanceTimeline.TimelineActivity;
import io.inspector.dto.InstanceVariables;
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

    static final Logger log = LoggerFactory.getLogger(InstanceDetailService.class);

    /** SPEC §4 size safeguard: structured previews cap at 256 KiB serialized. */
    public static final int STRUCTURED_PREVIEW_CAP_BYTES = 256 * 1024;

    /** R-SEM-19: max children RENDERED per node; totals stay exact via the query total. */
    static final int HIERARCHY_BREADTH_CAP = 50;

    /** Backstop over depth×breadth so a pathological tree cannot flood the BFF or the UI. */
    static final int HIERARCHY_MAX_RENDERED_NODES = 500;

    private static final ObjectMapper SIZER = new ObjectMapper();

    final EngineRegistry registry;
    final ProcessApiClient flowable;
    final ExternalJobApiClient externalJobs;
    final InspectorProperties props;
    private final ProtectedInstanceRepository protectedInstances;

    private final InstanceDiagramAssembler diagrams;
    private final InstanceVariablesAssembler variableLedger;
    private final InstanceJobsAssembler jobLanes;
    private final InstanceTasksAssembler taskTab;
    private final InstanceHierarchyAssembler hierarchyTab;
    private final InstanceTimelineAssembler timelineTab;

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
        this.diagrams = new InstanceDiagramAssembler(this);
        this.variableLedger = new InstanceVariablesAssembler(this);
        this.jobLanes = new InstanceJobsAssembler(this);
        this.taskTab = new InstanceTasksAssembler(this);
        this.hierarchyTab = new InstanceHierarchyAssembler(this);
        this.timelineTab = new InstanceTimelineAssembler(this);
    }

    /* ================= engine resolution — the read surface ================= */

    /**
     * The engine a detail READ targets (#248): resolved via {@link EngineRegistry#resolve} so a
     * registered-but-disabled engine keeps serving this page's read-only data — the same
     * survive-a-disable doctrine the audit/notes display already follows — instead of 404ing
     * "Unknown engine" one banner above a fully-rendered audit history on the very same page.
     * A genuinely unknown (or tombstoned/removed) id still 404s with that text. Mutations are
     * unaffected: every corrective action resolves through {@link EngineRegistry#require},
     * which keeps refusing a disabled engine as an operable target.
     */
    EngineConfig engineForRead(String engineId) {
        return registry.resolve(engineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown engine: " + engineId));
    }

    public InstanceDiagram diagram(String engineId, String instanceId) {
        return diagrams.diagram(engineId, instanceId);
    }

    public InstanceVariables variables(String engineId, String instanceId) {
        return variableLedger.variables(engineId, instanceId);
    }

    public VariableDto variable(String engineId, String instanceId, String name, String executionId) {
        return variableLedger.variable(engineId, instanceId, name, executionId);
    }

    public InstanceJobs jobs(String engineId, String instanceId) {
        return jobLanes.jobs(engineId, instanceId);
    }

    public String jobStacktrace(String engineId, String instanceId, String jobId, JobLaneKind lane) {
        return jobLanes.jobStacktrace(engineId, instanceId, jobId, lane);
    }

    public List<ExternalWorkerJobDto> externalWorkerJobs(String engineId, String instanceId) {
        return jobLanes.externalWorkerJobs(engineId, instanceId);
    }

    public InstanceTasks tasks(String engineId, String instanceId) {
        return taskTab.tasks(engineId, instanceId);
    }

    public InstanceHierarchy hierarchy(String engineId, String instanceId) {
        return hierarchyTab.hierarchy(engineId, instanceId);
    }

    public InstanceTimeline timeline(String engineId, String instanceId) {
        return timelineTab.timeline(engineId, instanceId);
    }

    /* ================= vitals ================= */

    public InstanceDetail vitals(String engineId, String instanceId) {
        EngineConfig engine = engineForRead(engineId);
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
     *
     * <p>Public (#166): {@code SearchService} reuses this exact logic for the results-grid row
     * mapping — the grid's status derivation had no termination-reason concept at all and fell
     * back to a plain COMPLETED chip, one derivation site drifting from the other rather than the
     * usual same-logic-in-two-places drift.
     */
    public static String terminationReason(Map<String, Object> historic, boolean ended) {
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

    static JobDto jobRow(Map<String, Object> job, JobLaneKind lane) {
        Object retries = job.get("retries");
        String handler = str(job, "jobHandlerType");
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
                str(job, "tenantId"),
                handler != null ? handler : str(job, "handlerType"));
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
                for (Map<String, Object> child :
                        hierarchyTab.childrenOf(engine, parent).dataOrEmpty()) {
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

    Lanes scanInstanceLanes(EngineConfig engine, String instanceId) {
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

    List<Map<String, Object>> instanceJobs(EngineConfig engine, String instanceId, JobLaneKind lane) {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("processInstanceId", instanceId);
        if (engine.tenantId() != null && !engine.tenantId().isBlank()) {
            filters.put("tenantId", engine.tenantId());
        }
        return flowable.listJobs(engine, CallPriority.INTERACTIVE, lane, filters, 0, engine.maxPageSizeOrDefault())
                .dataOrEmpty();
    }

    long deadLetterCount(EngineConfig engine, String instanceId) {
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
    List<CurrentActivity> currentActivities(EngineConfig engine, String instanceId) {
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
    String failingActivityIdOf(EngineConfig engine, Map<String, Object> job) {
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

    static void withTenant(EngineConfig engine, Map<String, Object> body) {
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

    static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
