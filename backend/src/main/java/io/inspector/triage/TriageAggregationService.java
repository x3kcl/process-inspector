package io.inspector.triage;

import io.inspector.client.FlowableEngineClient;
import io.inspector.client.FlowableEngineClient.FlowablePage;
import io.inspector.client.FlowableEngineClient.JobLaneKind;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.EngineDto;
import io.inspector.dto.ErrorGroup;
import io.inspector.dto.TriageDashboardResponse;
import io.inspector.dto.TriageDashboardResponse.PerEngineTriage;
import io.inspector.registry.EngineRegistry;
import io.inspector.triage.ErrorSignatureNormalizer.ErrorSignature;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The Stage 0 fan-out aggregator (SPEC §4 Stage 0, ARCH §2.2) — AGGREGATION-INDEPENDENT
 * by doctrine: it never reuses the M2a grid-search plan or its DTOs. Per engine, on
 * virtual threads:
 *
 * <ul>
 *   <li><b>Health strip / job lanes / alarms</b> — read from the registry, populated by
 *       the M1 scheduled probe. Zero new engine calls.</li>
 *   <li><b>Status counts</b> — ACTIVE/SUSPENDED/COMPLETED are query TOTALS only
 *       ({@code size=1}, no rows): ACTIVE and SUSPENDED from the runtime query's
 *       {@code suspended} flag, COMPLETED from the historic query's {@code finished:true}
 *       (completed instances do not exist on the runtime endpoint). Both fields filter
 *       correctly on 6.3.1/6.8/7.1 — canary-probed live 2026-07-06; sub-totals reconcile
 *       with the unfiltered totals. FAILED and RETRYING are SYNTHESIZED — Flowable has no
 *       such instance states (SPEC §3) — as distinct-instance counts harvested from the
 *       failure-lane scans below: FAILED = instances holding ≥1 dead-letter job, RETRYING =
 *       instances with withException jobs and none dead-lettered (FAILED precedence).
 *       Statuses collide by doctrine: a FAILED instance is still inside the ACTIVE (or
 *       SUSPENDED) total — the chips are flag tiers, not a partition. Under a truncated
 *       scan both are lower bounds, flagged by the existing {@code dlqScan} marker.</li>
 *   <li><b>Error groups</b> — dedicated capped scans of the failure lanes: dead-letter
 *       plus the RETRYING tier ({@code timer-jobs} AND {@code jobs} with
 *       {@code withException=true} — a failing job parks in the timer table between
 *       attempts). Snippets reduce through {@link ErrorSignatureNormalizer}; ONE
 *       representative stacktrace per group (capped) refines the root-cause class.</li>
 * </ul>
 *
 * Hygiene on every leg: CMMN-scoped jobs filtered out, {@code tenantId} threaded when
 * configured, truncation surfaced as {@code dlqScan:"truncated@N"} (counts become
 * labeled lower bounds, R-SEM-12), and a sick engine degrades to a {@code perEngine}
 * error envelope — never a failed response.
 */
@Service
public class TriageAggregationService {

    private static final Logger log = LoggerFactory.getLogger(TriageAggregationService.class);

    /** Failure lanes scanned for error groups; DEADLETTER is FAILED, the other two RETRYING. */
    private static final List<JobLaneKind> FAILURE_LANES =
            List.of(JobLaneKind.DEADLETTER, JobLaneKind.TIMER, JobLaneKind.EXECUTABLE);

    private final EngineRegistry registry;
    private final FlowableEngineClient flowable;
    private final InspectorProperties props;
    private final Clock clock;
    private final ExecutorService fanout = Executors.newVirtualThreadPerTaskExecutor();

    public TriageAggregationService(
            EngineRegistry registry, FlowableEngineClient flowable, InspectorProperties props, Clock clock) {
        this.registry = registry;
        this.flowable = flowable;
        this.props = props;
        this.clock = clock;
    }

    public TriageDashboardResponse aggregate() {
        Map<String, Future<EngineSlice>> futures = new LinkedHashMap<>();
        for (EngineConfig engine : registry.all()) {
            futures.put(engine.id(), fanout.submit(() -> sliceOf(engine)));
        }

        List<EngineDto> engines = new ArrayList<>();
        Map<String, Long> globalStatusCounts = new TreeMap<>();
        Map<String, Map<String, Long>> statusCountsByEngine = new LinkedHashMap<>();
        Map<String, PerEngineTriage> perEngine = new LinkedHashMap<>();
        Map<String, GroupAccumulator> groups = new LinkedHashMap<>();

        for (EngineConfig engine : registry.all()) {
            engines.add(EngineDto.from(engine, registry.healthOf(engine.id())));
            EngineSlice slice = resolve(engine.id(), futures.get(engine.id()));
            perEngine.put(engine.id(), slice.envelope());
            if (!slice.envelope().ok()) {
                continue;
            }
            statusCountsByEngine.put(engine.id(), slice.statusCounts());
            slice.statusCounts().forEach((status, total) -> globalStatusCounts.merge(status, total, Long::sum));
            for (EngineGroup group : slice.groups()) {
                groups.computeIfAbsent(group.signature().hash(), h -> new GroupAccumulator(group))
                        .add(engine.id(), group);
            }
        }

        List<ErrorGroup> errorGroups = groups.values().stream()
                .map(GroupAccumulator::toDto)
                .sorted(Comparator.comparingLong(ErrorGroup::total).reversed())
                .toList();
        return new TriageDashboardResponse(
                clock.instant().toString(), engines, globalStatusCounts, statusCountsByEngine, errorGroups, perEngine);
    }

    private EngineSlice resolve(String engineId, Future<EngineSlice> future) {
        try {
            return future.get();
        } catch (Exception ex) {
            log.warn("Triage slice for {} died unexpectedly: {}", engineId, ex.toString());
            return EngineSlice.failed(rootMessage(ex));
        }
    }

    /* ---------------- one engine's slice, on its own virtual thread ---------------- */

    private record EngineSlice(PerEngineTriage envelope, Map<String, Long> statusCounts, List<EngineGroup> groups) {
        static EngineSlice failed(String error) {
            return new EngineSlice(new PerEngineTriage(false, error, null, null), Map.of(), List.of());
        }
    }

    /** One refined error group as observed on ONE engine. */
    private record EngineGroup(
            ErrorSignature signature,
            String sampleRawMessage,
            long deadLetterCount,
            long retryingCount,
            Map<String, Long> countsByDefVersion) {}

    private EngineSlice sliceOf(EngineConfig engine) {
        try {
            // Independent legs fan out on virtual threads; the per-engine bulkhead (max 8)
            // is the do-no-harm ceiling, so this can never flood a struggling engine.
            Future<Long> active = fanout.submit(() -> runtimeTotal(engine, false));
            Future<Long> suspended = fanout.submit(() -> runtimeTotal(engine, true));
            Future<Long> completed = fanout.submit(() -> completedTotal(engine));
            Map<JobLaneKind, Future<LaneScan>> scans = new LinkedHashMap<>();
            for (JobLaneKind lane : FAILURE_LANES) {
                scans.put(lane, fanout.submit(() -> scanFailureLane(engine, lane)));
            }

            Map<String, Long> statusCounts = new TreeMap<>();
            statusCounts.put("ACTIVE", active.get());
            statusCounts.put("SUSPENDED", suspended.get());
            statusCounts.put("COMPLETED", completed.get());

            long scanned = 0;
            boolean truncated = false;
            Map<String, PreGroup> preGroups = new LinkedHashMap<>();
            Set<String> deadLetterInstances = new HashSet<>();
            Set<String> retryingInstances = new HashSet<>();
            for (Map.Entry<JobLaneKind, Future<LaneScan>> scan : scans.entrySet()) {
                LaneScan result = scan.getValue().get();
                scanned += result.jobs().size();
                truncated |= result.truncated();
                for (Map<String, Object> job : result.jobs()) {
                    accumulate(preGroups, job, scan.getKey());
                    String pid = bpmnProcessInstanceId(job);
                    if (pid != null) {
                        (scan.getKey() == JobLaneKind.DEADLETTER ? deadLetterInstances : retryingInstances).add(pid);
                    }
                }
            }
            // FAILED wins a collision (SPEC §3 precedence): an instance with both a
            // dead-letter job and a still-retrying one is FAILED, counted exactly once.
            retryingInstances.removeAll(deadLetterInstances);
            statusCounts.put("FAILED", (long) deadLetterInstances.size());
            statusCounts.put("RETRYING", (long) retryingInstances.size());

            // The dead-letter rows the join dropped for being out of BPMN scope — reported
            // only on engines that can be trusted to discriminate (scopeType capability, ~6.8+).
            var health = registry.healthOf(engine.id());
            boolean scopeTypeCapable = health != null
                    && health.capabilities() != null
                    && health.capabilities().scopeType();
            Integer outOfScope = outOfScopeDeadletters(
                    scans.get(JobLaneKind.DEADLETTER).get().jobs(), scopeTypeCapable);

            return new EngineSlice(
                    new PerEngineTriage(true, null, truncated ? "truncated@" + scanned : "complete", outOfScope),
                    statusCounts,
                    refine(engine, preGroups));
        } catch (Exception ex) {
            log.debug("Triage slice degraded for {}: {}", engine.id(), ex.toString());
            return EngineSlice.failed(rootMessage(ex));
        }
    }

    /* ---------------- status counts: totals only, never rows ---------------- */

    private long runtimeTotal(EngineConfig engine, boolean suspended) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("suspended", suspended);
        body.put("size", 1);
        withTenant(engine, body);
        FlowablePage page = flowable.queryRuntimeProcessInstances(engine, body);
        return page != null ? page.total() : 0;
    }

    private long completedTotal(EngineConfig engine) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("finished", true);
        body.put("size", 1);
        withTenant(engine, body);
        FlowablePage page = flowable.queryHistoricProcessInstances(engine, body);
        return page != null ? page.total() : 0;
    }

    private static void withTenant(EngineConfig engine, Map<String, Object> body) {
        if (engine.tenantId() != null && !engine.tenantId().isBlank()) {
            body.put("tenantId", engine.tenantId());
        }
    }

    /* ---------------- failure-lane scans ---------------- */

    private record LaneScan(List<Map<String, Object>> jobs, boolean truncated) {}

    /**
     * Pages one failure lane to exhaustion, bounded by the engine's {@code dlq-scan-cap}.
     * {@code withException=true} keeps the leg failure-only (the timer/executable lanes
     * hold mostly healthy jobs) and filters correctly on all three engine versions.
     */
    private LaneScan scanFailureLane(EngineConfig engine, JobLaneKind lane) {
        int cap = engine.dlqScanCapOrDefault();
        int pageSize = engine.maxPageSizeOrDefault();
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("withException", "true");
        if (engine.tenantId() != null && !engine.tenantId().isBlank()) {
            filters.put("tenantId", engine.tenantId());
        }
        List<Map<String, Object>> jobs = new ArrayList<>();
        long total = Long.MAX_VALUE;
        for (int start = 0; start < Math.min(total, cap); start += pageSize) {
            FlowablePage page = flowable.listJobs(engine, lane, filters, start, Math.min(pageSize, cap - start));
            if (page == null) {
                break;
            }
            total = page.total();
            if (page.dataOrEmpty().isEmpty()) {
                break;
            }
            jobs.addAll(page.dataOrEmpty());
        }
        return new LaneScan(jobs, total > jobs.size());
    }

    /* ---------------- reduction: snippet pre-groups → refined groups ---------------- */

    /** Jobs accumulated under one message-only (snippet) signature on one engine. */
    private static final class PreGroup {
        final ErrorSignature snippetSignature;
        final String sampleRawMessage;
        final String sampleJobId;
        final JobLaneKind sampleLane;
        long deadLetter;
        long retrying;
        final Map<String, Long> countsByDefVersion = new TreeMap<>();

        PreGroup(ErrorSignature snippetSignature, String sampleRawMessage, String sampleJobId, JobLaneKind lane) {
            this.snippetSignature = snippetSignature;
            this.sampleRawMessage = sampleRawMessage;
            this.sampleJobId = sampleJobId;
            this.sampleLane = lane;
        }
    }

    /**
     * CMMN hygiene (SPEC §3): flowable-rest shares job tables with the CMMN engine —
     * no processInstanceId (or an explicit non-bpmn scopeType, ~6.8+) is not ours.
     * Returns the BPMN process-instance id, or null when the job is out of scope.
     */
    private static String bpmnProcessInstanceId(Map<String, Object> job) {
        Object pid = job.get("processInstanceId");
        if (pid == null || pid.toString().isBlank()) {
            return null;
        }
        Object scopeType = job.get("scopeType");
        if (scopeType != null && !"bpmn".equalsIgnoreCase(scopeType.toString())) {
            return null;
        }
        return pid.toString();
    }

    /**
     * The dead-letter rows this aggregator EXCLUDES from every BPMN leg — jobs from another
     * engine sharing flowable-rest's job tables (CMMN, proven live: the process-api DLQ
     * projection lists them as {@code processInstanceId:null} orphans and serializes no
     * {@code scopeType} at all, so a missing process-instance id is the discriminator).
     * Counting them (rather than dropping them silently) lets the health strip's raw
     * dead-letter lane count reconcile with the process-scoped FAILED count.
     *
     * <p>Returns {@code null} — unknown, not zero — when the engine cannot be trusted to
     * discriminate scope ({@code scopeTypeCapable} false: pre-6.8, where 6.3.1 is
     * CMMN-dead-letter-blind and a confident zero would be a lie). A truncated DLQ scan
     * makes the count a lower bound, inheriting the slice's {@code dlqScan} marker.
     */
    static Integer outOfScopeDeadletters(List<Map<String, Object>> deadLetterJobs, boolean scopeTypeCapable) {
        if (!scopeTypeCapable) {
            return null;
        }
        int count = 0;
        for (Map<String, Object> job : deadLetterJobs) {
            if (bpmnProcessInstanceId(job) == null) {
                count++;
            }
        }
        return count;
    }

    private void accumulate(Map<String, PreGroup> preGroups, Map<String, Object> job, JobLaneKind lane) {
        if (bpmnProcessInstanceId(job) == null) {
            return;
        }
        String rawMessage = job.get("exceptionMessage") != null
                ? job.get("exceptionMessage").toString()
                : null;
        ErrorSignature signature = ErrorSignatureNormalizer.normalize(rawMessage);
        PreGroup group = preGroups.computeIfAbsent(
                signature.hash(), h -> new PreGroup(signature, rawMessage, String.valueOf(job.get("id")), lane));
        if (lane == JobLaneKind.DEADLETTER) {
            group.deadLetter++;
        } else {
            group.retrying++;
        }
        group.countsByDefVersion.merge(defVersionKey(job), 1L, Long::sum);
    }

    /**
     * No engine serializes definition key/version on job rows — both derive from
     * {@code processDefinitionId} ({@code key:version:uuid}, stable across 6.3.1/6.8/7.1).
     */
    private static String defVersionKey(Map<String, Object> job) {
        Object definitionId = job.get("processDefinitionId");
        String[] parts = definitionId != null ? definitionId.toString().split(":") : new String[0];
        return parts.length >= 2 ? parts[0] + ":v" + parts[1] : "unknown:v?";
    }

    /**
     * Refines each pre-group with ONE representative stacktrace (bounded by
     * {@code stacktrace-sample-cap}, largest groups first — N+1 on GROUPS, never on jobs)
     * to resolve the root-cause class per the R-SEM-03 unwrap contract, then zero-fills
     * sibling definition versions so version-specific failures are visible ("v47: 312,
     * v46: 0"). Refinement failures degrade to the message-only signature — never the slice.
     */
    private List<EngineGroup> refine(EngineConfig engine, Map<String, PreGroup> preGroups) {
        Map<String, List<String>> versionsByKey = definitionVersions(engine, preGroups);
        List<PreGroup> bySize = preGroups.values().stream()
                .sorted(Comparator.comparingLong((PreGroup g) -> g.deadLetter + g.retrying)
                        .reversed())
                .toList();
        int sampleBudget = props.triageOrDefault().stacktraceSampleCapOrDefault();
        List<EngineGroup> groups = new ArrayList<>();
        for (PreGroup group : bySize) {
            ErrorSignature signature = group.snippetSignature;
            if (sampleBudget-- > 0) {
                try {
                    String stacktrace = flowable.jobExceptionStacktrace(engine, group.sampleLane, group.sampleJobId);
                    if (stacktrace != null && !stacktrace.isBlank()) {
                        signature = ErrorSignatureNormalizer.normalize(stacktrace);
                    }
                } catch (Exception ex) {
                    log.debug("Stacktrace refinement failed on {}: {}", engine.id(), ex.toString());
                }
            }
            Map<String, Long> counts = new TreeMap<>(group.countsByDefVersion);
            group.countsByDefVersion.keySet().stream()
                    .map(key -> key.substring(0, key.lastIndexOf(":v")))
                    .distinct()
                    .forEach(defKey -> versionsByKey
                            .getOrDefault(defKey, List.of())
                            .forEach(version -> counts.putIfAbsent(defKey + ":v" + version, 0L)));
            groups.add(new EngineGroup(signature, group.sampleRawMessage, group.deadLetter, group.retrying, counts));
        }
        return groups;
    }

    /** All deployed versions per definition key seen in any group (for the zero-fill). */
    private Map<String, List<String>> definitionVersions(EngineConfig engine, Map<String, PreGroup> preGroups) {
        Map<String, List<String>> versionsByKey = new LinkedHashMap<>();
        preGroups.values().stream()
                .flatMap(g -> g.countsByDefVersion.keySet().stream())
                .map(key -> key.substring(0, key.lastIndexOf(":v")))
                .distinct()
                .filter(key -> !key.equals("unknown"))
                .forEach(defKey -> {
                    try {
                        FlowablePage page = flowable.listProcessDefinitionsByKey(engine, defKey, 50);
                        versionsByKey.put(
                                defKey,
                                page.dataOrEmpty().stream()
                                        .map(def -> String.valueOf(def.get("version")))
                                        .toList());
                    } catch (Exception ex) {
                        log.debug("Version zero-fill skipped for {} on {}: {}", defKey, engine.id(), ex.toString());
                    }
                });
        return versionsByKey;
    }

    /* ---------------- cross-engine merge ---------------- */

    private static final class GroupAccumulator {
        private final ErrorSignature signature;
        private final String sampleRawMessage;
        private long deadLetter;
        private long retrying;
        private final Map<String, Map<String, Long>> countsByEngine = new LinkedHashMap<>();

        GroupAccumulator(EngineGroup first) {
            this.signature = first.signature();
            this.sampleRawMessage = first.sampleRawMessage();
        }

        void add(String engineId, EngineGroup group) {
            deadLetter += group.deadLetterCount();
            retrying += group.retryingCount();
            // merge-sum: two snippet pre-groups on one engine may refine to the SAME
            // root-cause signature (different expressions, one exception class).
            Map<String, Long> engineCounts = countsByEngine.computeIfAbsent(engineId, id -> new TreeMap<>());
            group.countsByDefVersion().forEach((defVersion, count) -> engineCounts.merge(defVersion, count, Long::sum));
        }

        ErrorGroup toDto() {
            return new ErrorGroup(
                    signature.hash(),
                    signature.algoVersion(),
                    signature.exceptionClass(),
                    signature.normalizedMessage(),
                    sampleRawMessage,
                    deadLetter + retrying,
                    deadLetter,
                    retrying,
                    countsByEngine);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.toString();
    }

    @PreDestroy
    void shutdown() {
        fanout.shutdownNow();
    }
}
