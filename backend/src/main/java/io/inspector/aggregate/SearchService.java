package io.inspector.aggregate;

import io.inspector.client.FlowableEngineClient;
import io.inspector.client.FlowableEngineClient.FlowablePage;
import io.inspector.client.FlowableEngineClient.JobLaneKind;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.InstanceStatusFlags;
import io.inspector.dto.ProcessInstanceRow;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.dto.SearchResponse;
import io.inspector.dto.SearchResponse.EngineResult;
import io.inspector.registry.EngineRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The API aggregator (ARCHITECTURE §2.3, the CORRECTED status join): one search plan per
 * selected engine, fanned out on virtual threads with per-engine budgets, rows merged and
 * sorted in the BFF. Status is derived as FLAGS ({@link InstanceStatusFlags}); the pure
 * join semantics live in {@link StatusJoin} (rung-1 tested), the engine I/O here.
 *
 * <p>Plan selection per requested status set:
 * <ul>
 *   <li><b>INVERTED</b> (FAILED/RETRYING only): drive FROM the job queues — page the DLQ to
 *       exhaustion (bounded by {@code dlq-scan-cap}, definition filter pushed down), plus
 *       the two withException lanes for the RETRYING tier; resolve call-activity children up
 *       the {@code superProcessInstanceId} chain (batched level-by-level, depth-capped,
 *       cycle-guarded); hydrate via the historic query with {@code processInstanceIds}.</li>
 *   <li><b>MIXED</b>: primary historic query per filters, then per-page enrichment of exactly
 *       the displayed rows: bulk runtime suspended state (with an ignored-filter fallback for
 *       legacy engines), bounded-N+1 DLQ membership, and capped withException lane scans.</li>
 * </ul>
 *
 * A capped scan never impersonates a complete one: the per-engine envelope carries
 * {@code dlqScan/failingScan = "truncated@N"} and every downstream count is a lower bound.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final EngineRegistry registry;
    private final FlowableEngineClient flowable;
    private final InspectorProperties props;
    private final ExecutorService fanout = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore engineSlots;

    public SearchService(EngineRegistry registry, FlowableEngineClient flowable, InspectorProperties props) {
        this.registry = registry;
        this.flowable = flowable;
        this.props = props;
        this.engineSlots = new Semaphore(props.fanoutParallelism() != null ? props.fanoutParallelism() : 8);
    }

    public SearchResponse search(SearchRequest request) {
        BffFilters bff = BffFilters.of(request); // validates the failure window → 400 on bad ISO
        String sortBy = notBlank(request.sortBy()) ? request.sortBy() : "startTime";
        if (!sortBy.equals("startTime") && !sortBy.equals("failureTime")) {
            throw new IllegalArgumentException("sortBy must be 'startTime' or 'failureTime', got '" + sortBy + "'");
        }
        List<EngineConfig> targets = resolveTargets(request);
        if (request.variables() != null
                && !request.variables().isEmpty()
                && !notBlank(request.processDefinitionKey())) {
            // SPEC §8 guardrail: variable-value search hits typically-unindexed engine tables
            // (ACT_RU/HI_VARINST) — without a definition to narrow it, every targeted engine
            // pays a table scan.
            log.warn(
                    "Variable search without a processDefinitionKey — unindexed variable-table scan on {} engine(s); narrow by definition",
                    targets.size());
        }
        Set<InstanceStatus> wanted = EnumSet.copyOf(request.effectiveStatuses());
        Map<String, EngineResult> perEngine = new ConcurrentHashMap<>();

        Map<EngineConfig, CompletableFuture<EngineSlice>> futures = new LinkedHashMap<>();
        for (EngineConfig engine : targets) {
            futures.put(
                    engine,
                    CompletableFuture.supplyAsync(
                            () -> {
                                engineSlots.acquireUninterruptibly();
                                try {
                                    return searchOneEngine(engine, request, wanted, bff);
                                } finally {
                                    engineSlots.release();
                                }
                            },
                            fanout));
        }

        List<ProcessInstanceRow> rows = new ArrayList<>();
        Map<InstanceStatus, Long> statusCounts = new EnumMap<>(InstanceStatus.class);
        for (Map.Entry<EngineConfig, CompletableFuture<EngineSlice>> e : futures.entrySet()) {
            EngineConfig engine = e.getKey();
            // Generous outer guard; the real limits are the per-call read timeout, the
            // per-engine breaker and the bounded scans inside the plan.
            long budgetMs = engine.timeoutsOrDefault().read() * 6L + 2000;
            try {
                EngineSlice slice = e.getValue().get(budgetMs, TimeUnit.MILLISECONDS);
                rows.addAll(slice.rows());
                slice.counts().forEach((status, n) -> statusCounts.merge(status, n, Long::sum));
                perEngine.put(
                        engine.id(),
                        EngineResult.success(slice.rows().size(), slice.total(), slice.dlqScan(), slice.failingScan()));
            } catch (TimeoutException te) {
                e.getValue().cancel(true);
                perEngine.put(engine.id(), EngineResult.failure("timeout after " + budgetMs + "ms"));
            } catch (Exception ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                log.warn("Engine {} search failed: {}", engine.id(), cause.toString());
                perEngine.put(engine.id(), EngineResult.failure(cause.getMessage()));
            }
        }

        if (sortBy.equals("failureTime")) {
            // Cross-engine timestamp formats differ (offset vs Z form) — compare as Instants.
            rows.sort(Comparator.comparing(
                    r -> StatusJoin.parseInstant(r.failureTime()), Comparator.nullsLast(Comparator.reverseOrder())));
        } else {
            rows.sort(Comparator.comparing(
                    ProcessInstanceRow::startTime, Comparator.nullsLast(Comparator.reverseOrder())));
        }
        return new SearchResponse(rows, new HashMap<>(perEngine), statusCounts, null, null);
    }

    /** One engine's contribution: filtered rows, honesty markers, and the facet counts. */
    private record EngineSlice(
            List<ProcessInstanceRow> rows,
            long total,
            String dlqScan,
            String failingScan,
            Map<InstanceStatus, Long> counts) {}

    /**
     * The parsed BFF-side filters (M2b, SPEC §8): the failure window + error text apply to
     * job rows on the scan legs (Flowable cannot query instances by dead-letter evidence);
     * currentActivity is matched against unfinished activity id/name per candidate row.
     */
    private record BffFilters(Instant failedAfter, Instant failedBefore, String errorText, String currentActivity) {
        static BffFilters of(SearchRequest req) {
            return new BffFilters(
                    parseBound(req.failureTimeAfter(), "failureTimeAfter"),
                    parseBound(req.failureTimeBefore(), "failureTimeBefore"),
                    notBlank(req.errorText()) ? req.errorText() : null,
                    notBlank(req.currentActivity()) ? req.currentActivity() : null);
        }

        boolean anyFailureFilter() {
            return failedAfter != null || failedBefore != null || errorText != null;
        }

        private static Instant parseBound(String iso, String field) {
            if (iso == null || iso.isBlank()) return null;
            Instant bound = StatusJoin.parseInstant(iso);
            if (bound == null) {
                throw new IllegalArgumentException(field + " is not an ISO-8601 timestamp: '" + iso + "'");
            }
            return bound;
        }
    }

    /** Failure evidence folded per instance: newest dead-letter createTime + first snippet. */
    private record Failure(String failureTime, String snippet) {
        static Failure merge(Failure a, Failure b) {
            String time = a.failureTime() == null
                    ? b.failureTime()
                    : b.failureTime() == null
                            ? a.failureTime()
                            : (a.failureTime().compareTo(b.failureTime()) >= 0 ? a.failureTime() : b.failureTime());
            return new Failure(time, a.snippet() != null ? a.snippet() : b.snippet());
        }
    }

    private EngineSlice searchOneEngine(
            EngineConfig engine, SearchRequest req, Set<InstanceStatus> wanted, BffFilters bff) {
        if (notBlank(req.businessKeyLike()) && businessKeyLikeIgnored(engine)) {
            // 6.3-era cliff (ARCH §2.5 family): the field is silently DROPPED, so trusting the
            // result would return confidently unfiltered rows. Degrade this engine honestly.
            throw new IllegalStateException(
                    "businessKeyLike is not supported by this engine version (the filter is silently ignored)"
                            + " — use exact businessKey");
        }
        int pageSize = Math.min(
                req.pageSize() != null && req.pageSize() > 0 ? req.pageSize() : engine.maxPageSizeOrDefault(),
                engine.maxPageSizeOrDefault());
        return switch (StatusJoin.planFor(wanted)) {
            case INVERTED -> invertedPlan(engine, req, wanted, pageSize, bff);
            case MIXED -> mixedPlan(engine, req, wanted, pageSize, bff);
        };
    }

    /**
     * Call-time detection of the ignored-field cliff, same doctrine as the runtime
     * {@code processInstanceIds} fallback: an impossible-match canary that still returns
     * rows proves the engine dropped the filter. One size=1 query, only when the search
     * actually uses businessKeyLike. (An empty engine passes trivially — and trivially
     * returns no wrong rows either.)
     */
    private boolean businessKeyLikeIgnored(EngineConfig engine) {
        Map<String, Object> canary = new HashMap<>();
        canary.put("processBusinessKeyLike", "%__inspector_bkl_canary__%");
        canary.put("size", 1);
        tenant(engine).ifPresent(t -> canary.put("tenantId", t));
        return flowable.queryHistoricProcessInstances(engine, canary).total() > 0;
    }

    /* ================= INVERTED plan — drive FROM the job queues ================= */

    private EngineSlice invertedPlan(
            EngineConfig engine, SearchRequest req, Set<InstanceStatus> wanted, int pageSize, BffFilters bff) {
        LaneScan dlq = wanted.contains(InstanceStatus.FAILED)
                ? scanLane(engine, JobLaneKind.DEADLETTER, Map.of(), req.processDefinitionKey())
                : LaneScan.empty();
        LaneScan failing = wanted.contains(InstanceStatus.RETRYING)
                ? scanExceptionLanes(engine, req.processDefinitionKey())
                : LaneScan.empty();

        // BFF-side failure filters bite HERE — on the job rows, before grouping and before
        // root resolution, so a filtered-out child failure never rolls up (SPEC §8).
        Map<String, Failure> dlqByInstance = indexByInstance(applyFailureFilters(dlq.jobs(), bff));
        Map<String, Failure> failingByInstance = indexByInstance(applyFailureFilters(failing.jobs(), bff));

        // Roll-up: resolve every dead-lettered instance to its call-activity root. Parent
        // links come from BATCHED historic queries (level by level) — never one engine call
        // per instance; the walk itself is pure and cycle-guarded (StatusJoin).
        Map<String, String> parentMap = resolveParentMap(engine, dlqByInstance.keySet());
        Set<String> rolledUpRoots = new HashSet<>();
        Map<String, Failure> rollupFailure = new HashMap<>();
        for (Map.Entry<String, Failure> child : dlqByInstance.entrySet()) {
            String root = StatusJoin.resolveRoot(child.getKey(), parentMap::get, props.hierarchyMaxDepthOrDefault());
            if (!root.equals(child.getKey())) {
                rolledUpRoots.add(root);
                rollupFailure.merge(root, child.getValue(), Failure::merge);
            }
        }

        Set<String> ids = new HashSet<>();
        ids.addAll(dlqByInstance.keySet());
        ids.addAll(rolledUpRoots);
        ids.addAll(failingByInstance.keySet());
        if (ids.isEmpty()) {
            return new EngineSlice(List.of(), 0, dlq.marker(), failing.marker(), Map.of());
        }

        // Hydration: ONE historic query carrying the id set plus the remaining filters —
        // the engine (not the BFF) evaluates businessKey/time/variable predicates.
        Map<String, Object> body = historicBody(engine, req, pageSize);
        body.put("processInstanceIds", List.copyOf(ids));
        FlowablePage page = flowable.queryHistoricProcessInstances(engine, body);
        List<Map<String, Object>> data = page.dataOrEmpty();

        Map<String, Boolean> suspendedById = suspendedFlags(engine, openIds(data));
        Set<String> activityMatched = activityMatches(engine, openIds(data), bff);

        List<ProcessInstanceRow> rows = new ArrayList<>();
        Map<InstanceStatus, Long> counts = new EnumMap<>(InstanceStatus.class);
        for (Map<String, Object> pi : data) {
            String id = str(pi, "id");
            InstanceStatusFlags flags = new InstanceStatusFlags(
                    str(pi, "endTime") != null,
                    suspendedById.getOrDefault(id, false),
                    dlqByInstance.containsKey(id),
                    failingByInstance.containsKey(id),
                    rolledUpRoots.contains(id));
            if (activityMatched != null && !activityMatched.contains(id)) continue;
            // Facets count every candidate that survived the non-status filters (SPEC §8),
            // keyed by primary chip, BEFORE the status predicate — lower bounds under caps.
            counts.merge(flags.primaryStatus(), 1L, Long::sum);
            if (!StatusJoin.matches(flags, wanted)) continue;
            Failure failure = dlqByInstance.getOrDefault(id, rollupFailure.getOrDefault(id, failingByInstance.get(id)));
            rows.add(row(engine, pi, flags, failure));
        }
        return new EngineSlice(rows, page.total(), dlq.marker(), failing.marker(), counts);
    }

    /* ================= MIXED plan — historic first, enrich the page ================= */

    private EngineSlice mixedPlan(
            EngineConfig engine, SearchRequest req, Set<InstanceStatus> wanted, int pageSize, BffFilters bff) {
        Map<String, Object> body = historicBody(engine, req, pageSize);
        // Narrow finished when the status OR-set is one-sided (pure optimization).
        boolean wantsOpen = wanted.stream().anyMatch(s -> s != InstanceStatus.COMPLETED);
        boolean wantsCompleted = wanted.contains(InstanceStatus.COMPLETED);
        if (wantsCompleted && !wantsOpen) body.put("finished", true);
        else if (wantsOpen && !wantsCompleted) body.put("finished", false);

        FlowablePage historic = flowable.queryHistoricProcessInstances(engine, body);
        List<Map<String, Object>> data = historic.dataOrEmpty();
        Set<String> open = openIds(data);

        Map<String, Boolean> suspendedById = suspendedFlags(engine, open);
        // DLQ membership per displayed OPEN row — the sanctioned bounded N+1 over one page
        // (ARCH §2.3), parallelized on virtual threads, throttled by the engine bulkhead.
        Map<String, Failure> dlqByInstance = deadLetterMembership(engine, open, bff);
        // RETRYING tier: two capped lane scans → membership set (the failing lanes are small).
        LaneScan failing = scanExceptionLanes(engine, req.processDefinitionKey());
        Map<String, Failure> failingByInstance = indexByInstance(applyFailureFilters(failing.jobs(), bff));
        Set<String> activityMatched = activityMatches(engine, open, bff);

        List<ProcessInstanceRow> rows = new ArrayList<>();
        Map<InstanceStatus, Long> counts = new EnumMap<>(InstanceStatus.class);
        for (Map<String, Object> pi : data) {
            String id = str(pi, "id");
            InstanceStatusFlags flags = new InstanceStatusFlags(
                    str(pi, "endTime") != null,
                    suspendedById.getOrDefault(id, false),
                    dlqByInstance.containsKey(id),
                    failingByInstance.containsKey(id),
                    false); // subprocess roll-up is a property of the INVERTED plan
            Failure failure = dlqByInstance.getOrDefault(id, failingByInstance.get(id));
            // A failure-evidence filter means only failure-bearing rows can match (SPEC §8).
            if (bff.anyFailureFilter() && failure == null) continue;
            if (activityMatched != null && !activityMatched.contains(id)) continue;
            counts.merge(flags.primaryStatus(), 1L, Long::sum);
            if (!StatusJoin.matches(flags, wanted)) continue;
            rows.add(row(engine, pi, flags, failure));
        }
        return new EngineSlice(rows, historic.total(), null, failing.marker(), counts);
    }

    /**
     * The current-activity leg (SPEC §8: "activity id/name contains"): unfinished historic
     * activities per candidate OPEN row — a bounded N+1 like the DLQ membership check, on
     * virtual threads behind the engine bulkhead. Null = filter absent (no restriction);
     * completed rows can never match (they have no unfinished activities).
     */
    private Set<String> activityMatches(EngineConfig engine, Set<String> openIds, BffFilters bff) {
        if (bff.currentActivity() == null) return null;
        if (openIds.isEmpty()) return Set.of();
        String needle = bff.currentActivity().toLowerCase(Locale.ROOT);
        Map<String, Boolean> hits = parallelPerId(openIds, id -> {
            for (Map<String, Object> activity : flowable.listUnfinishedActivities(
                            engine, id, engine.tenantId(), engine.maxPageSizeOrDefault())
                    .dataOrEmpty()) {
                if (containsIgnoreCase(str(activity, "activityId"), needle)
                        || containsIgnoreCase(str(activity, "activityName"), needle)) {
                    return true;
                }
            }
            return false;
        });
        Set<String> matched = new HashSet<>();
        hits.forEach((id, hit) -> {
            if (Boolean.TRUE.equals(hit)) matched.add(id);
        });
        return matched;
    }

    private static boolean containsIgnoreCase(String haystack, String lowerCaseNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowerCaseNeedle);
    }

    /** M2b BFF-side failure filters over one scan leg's job rows (pure predicate in StatusJoin). */
    private static List<Map<String, Object>> applyFailureFilters(List<Map<String, Object>> jobs, BffFilters bff) {
        if (!bff.anyFailureFilter()) return jobs;
        return jobs.stream()
                .filter(job -> StatusJoin.jobMatchesFailureFilters(
                        job, bff.failedAfter(), bff.failedBefore(), bff.errorText()))
                .toList();
    }

    /* ================= scan legs ================= */

    private record LaneScan(List<Map<String, Object>> jobs, boolean truncated, int scanned) {
        static LaneScan empty() {
            return new LaneScan(List.of(), false, 0);
        }

        LaneScan and(LaneScan other) {
            List<Map<String, Object>> all = new ArrayList<>(jobs);
            all.addAll(other.jobs());
            return new LaneScan(all, truncated || other.truncated(), scanned + other.scanned());
        }

        /** ARCH §2.3 honesty marker: {@code "truncated@N"}, null when the scan completed. */
        String marker() {
            return truncated ? "truncated@" + scanned : null;
        }
    }

    /** RETRYING tier = BOTH withException lanes: a failing async job parks in the TIMER table between attempts. */
    private LaneScan scanExceptionLanes(EngineConfig engine, String definitionKey) {
        Map<String, String> withException = Map.of("withException", "true");
        return scanLane(engine, JobLaneKind.EXECUTABLE, withException, definitionKey)
                .and(scanLane(engine, JobLaneKind.TIMER, withException, definitionKey));
    }

    /**
     * Bounded exhaustive paging of one job lane — NEVER a single unpaged fetch (default page
     * size is 10; anything past it would silently declassify FAILED → ACTIVE). A definition
     * filter is pushed down as concrete per-version {@code processDefinitionId}s.
     */
    private LaneScan scanLane(
            EngineConfig engine, JobLaneKind lane, Map<String, String> baseFilters, String definitionKey) {
        int cap = engine.dlqScanCapOrDefault();
        int pageSize = engine.maxPageSizeOrDefault();
        List<String> definitionIds = definitionKey == null || definitionKey.isBlank()
                ? java.util.Collections.singletonList(null)
                : resolveDefinitionIds(engine, definitionKey);

        List<Map<String, Object>> jobs = new ArrayList<>();
        int scanned = 0;
        boolean truncated = false;
        for (String definitionId : definitionIds) {
            Map<String, String> filters = new HashMap<>(baseFilters);
            if (definitionId != null) filters.put("processDefinitionId", definitionId);
            tenant(engine).ifPresent(t -> filters.put("tenantId", t));

            int start = 0;
            while (true) {
                int size = Math.min(pageSize, cap - scanned);
                if (size <= 0) {
                    truncated = true;
                    break;
                }
                FlowablePage page = flowable.listJobs(engine, lane, filters, start, size);
                List<Map<String, Object>> batch = page.dataOrEmpty();
                scanned += batch.size();
                jobs.addAll(batch);
                start += batch.size();
                if (batch.isEmpty() || start >= page.total()) break; // lane exhausted
            }
            if (truncated) break;
        }
        return new LaneScan(jobs, truncated, scanned);
    }

    /** CMMN-filtered fold of job rows into per-instance failure evidence. */
    private static Map<String, Failure> indexByInstance(List<Map<String, Object>> jobs) {
        Map<String, Failure> index = new HashMap<>();
        for (Map<String, Object> job : jobs) {
            if (!StatusJoin.isBpmnJob(job)) continue; // shared job tables — drop CMMN scopes
            index.merge(
                    str(job, "processInstanceId"),
                    new Failure(str(job, "createTime"), str(job, "exceptionMessage")),
                    Failure::merge);
        }
        return index;
    }

    /* ================= enrichment legs ================= */

    /**
     * Suspended state for the displayed open rows: ONE bulk runtime query — with a fallback
     * to per-id GETs when the engine silently ignores {@code processInstanceIds} (proven on
     * 6.3.1: unknown query fields are dropped and the result is UNFILTERED; trusting it
     * would join foreign rows). Detection: any returned id outside the requested set, or an
     * impossible total.
     */
    private Map<String, Boolean> suspendedFlags(EngineConfig engine, Set<String> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<String, Object> body = new HashMap<>();
        body.put("processInstanceIds", List.copyOf(ids));
        body.put("size", ids.size());
        tenant(engine).ifPresent(t -> body.put("tenantId", t));
        FlowablePage page = flowable.queryRuntimeProcessInstances(engine, body);

        Map<String, Boolean> byId = new HashMap<>();
        boolean filterIgnored = page.total() > ids.size();
        for (Map<String, Object> pi : page.dataOrEmpty()) {
            String id = str(pi, "id");
            if (!ids.contains(id)) {
                filterIgnored = true;
                break;
            }
            byId.put(id, Boolean.TRUE.equals(pi.get("suspended")));
        }
        if (!filterIgnored) return byId;

        log.debug("Engine {} ignored processInstanceIds on the runtime query — per-id fallback", engine.id());
        return parallelPerId(ids, id -> {
            Map<String, Object> pi = flowable.getRuntimeProcessInstance(engine, id);
            return pi != null && Boolean.TRUE.equals(pi.get("suspended"));
        });
    }

    /** Bounded N+1 DLQ membership for one page of open rows, parallel on virtual threads. */
    private Map<String, Failure> deadLetterMembership(EngineConfig engine, Set<String> ids, BffFilters bff) {
        if (ids.isEmpty()) return Map.of();
        Map<String, Failure> result = new ConcurrentHashMap<>();
        parallelPerId(ids, id -> {
            Map<String, String> filters = new HashMap<>();
            filters.put("processInstanceId", id);
            tenant(engine).ifPresent(t -> filters.put("tenantId", t));
            FlowablePage page =
                    flowable.listJobs(engine, JobLaneKind.DEADLETTER, filters, 0, engine.maxPageSizeOrDefault());
            // Same failure-window/error-text semantics as the inverted plan's scan legs.
            Map<String, Failure> perInstance = indexByInstance(applyFailureFilters(page.dataOrEmpty(), bff));
            Failure failure = perInstance.get(id);
            if (failure != null) result.put(id, failure);
            return true;
        });
        return result;
    }

    /**
     * Parent links for the roll-up, resolved level by level with BATCHED historic queries
     * (chunked to the engine page cap). Depth is bounded here AND in the walk itself.
     */
    private Map<String, String> resolveParentMap(EngineConfig engine, Set<String> instanceIds) {
        Map<String, String> parentMap = new HashMap<>();
        Set<String> frontier = new HashSet<>(instanceIds);
        for (int depth = 0; depth < props.hierarchyMaxDepthOrDefault() && !frontier.isEmpty(); depth++) {
            Map<String, Map<String, Object>> rows = fetchHistoricByIds(engine, frontier);
            Set<String> next = new HashSet<>();
            for (String id : frontier) {
                Map<String, Object> row = rows.get(id);
                String parent = row != null ? str(row, "superProcessInstanceId") : null;
                parentMap.put(id, parent);
                if (parent != null && !parentMap.containsKey(parent)) next.add(parent);
            }
            frontier = next;
        }
        return parentMap;
    }

    /** Historic rows by id, chunked to the engine's page cap, chunks fetched in parallel. */
    private Map<String, Map<String, Object>> fetchHistoricByIds(EngineConfig engine, Collection<String> ids) {
        List<String> all = List.copyOf(ids);
        int chunkSize = engine.maxPageSizeOrDefault();
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < all.size(); i += chunkSize) {
            chunks.add(all.subList(i, Math.min(i + chunkSize, all.size())));
        }
        Map<String, Map<String, Object>> byId = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (List<String> chunk : chunks) {
            futures.add(CompletableFuture.runAsync(
                    () -> {
                        Map<String, Object> body = new HashMap<>();
                        body.put("processInstanceIds", chunk);
                        body.put("size", chunk.size());
                        tenant(engine).ifPresent(t -> body.put("tenantId", t));
                        for (Map<String, Object> row : flowable.queryHistoricProcessInstances(engine, body)
                                .dataOrEmpty()) {
                            byId.put(str(row, "id"), row);
                        }
                    },
                    fanout));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        return byId;
    }

    private <T> Map<String, T> parallelPerId(Set<String> ids, java.util.function.Function<String, T> lookup) {
        Map<String, T> out = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String id : ids) {
            futures.add(CompletableFuture.runAsync(() -> out.put(id, lookup.apply(id)), fanout));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        return out;
    }

    /* ================= row assembly & query bodies ================= */

    private ProcessInstanceRow row(
            EngineConfig engine, Map<String, Object> pi, InstanceStatusFlags flags, Failure failure) {
        String id = str(pi, "id");
        String definitionId = str(pi, "processDefinitionId");
        return new ProcessInstanceRow(
                engine.id() + ":" + id,
                engine.id(),
                engine.name(),
                engine.accentColor(),
                id,
                str(pi, "businessKey"),
                definitionKeyOf(definitionId),
                str(pi, "processDefinitionName"),
                definitionVersionOf(definitionId),
                str(pi, "tenantId"),
                "bpmn", // carried from day one for CMMN support (SPEC §3)
                flags.primaryStatus(),
                flags,
                str(pi, "startTime"),
                str(pi, "endTime"),
                failure != null ? failure.failureTime() : null,
                failure != null ? failure.snippet() : null);
    }

    /**
     * Shared scalar/variable filters (AND semantics, SPEC §3.A). No engine serializes
     * processDefinitionKey/version on historic rows across the version matrix — both are
     * derived from processDefinitionId ({@code key:version:uuid}).
     */
    private Map<String, Object> historicBody(EngineConfig engine, SearchRequest req, int pageSize) {
        Map<String, Object> body = new HashMap<>();
        if (notBlank(req.processDefinitionKey())) body.put("processDefinitionKey", req.processDefinitionKey());
        if (notBlank(req.businessKey())) body.put("processBusinessKey", req.businessKey());
        // Substring semantics: wrap unless the caller already sent a % pattern. Engines that
        // silently drop this field were rejected earlier (businessKeyLikeIgnored canary).
        if (notBlank(req.businessKeyLike())) body.put("processBusinessKeyLike", likePattern(req.businessKeyLike()));
        if (notBlank(req.startedAfter())) body.put("startedAfter", req.startedAfter());
        if (notBlank(req.startedBefore())) body.put("startedBefore", req.startedBefore());
        tenant(engine).ifPresent(t -> body.put("tenantId", t));
        if (req.variables() != null && !req.variables().isEmpty()) {
            body.put(
                    "variables",
                    req.variables().stream()
                            .map(v -> {
                                Map<String, Object> m = new HashMap<>();
                                m.put("name", v.name());
                                m.put("value", v.value());
                                m.put("operation", v.operation() != null ? v.operation() : "equals");
                                if (v.type() != null) m.put("type", v.type());
                                return m;
                            })
                            .toList());
        }
        body.put("size", pageSize);
        body.put("sort", "startTime");
        body.put("order", "desc");
        return body;
    }

    private List<String> resolveDefinitionIds(EngineConfig engine, String key) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> def : flowable.listProcessDefinitionsByKey(engine, key, engine.maxPageSizeOrDefault())
                .dataOrEmpty()) {
            String id = str(def, "id");
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private static Set<String> openIds(List<Map<String, Object>> historicRows) {
        Set<String> open = new HashSet<>();
        for (Map<String, Object> pi : historicRows) {
            if (str(pi, "endTime") == null) open.add(str(pi, "id"));
        }
        return open;
    }

    private static java.util.Optional<String> tenant(EngineConfig engine) {
        return notBlank(engine.tenantId()) ? java.util.Optional.of(engine.tenantId()) : java.util.Optional.empty();
    }

    private static String definitionKeyOf(String definitionId) {
        if (definitionId == null) return null;
        int i = definitionId.indexOf(':');
        return i > 0 ? definitionId.substring(0, i) : definitionId;
    }

    private static Integer definitionVersionOf(String definitionId) {
        if (definitionId == null) return null;
        String[] parts = definitionId.split(":", 3);
        try {
            return parts.length >= 2 ? Integer.valueOf(parts[1]) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<EngineConfig> resolveTargets(SearchRequest req) {
        if (req.engineIds() == null || req.engineIds().isEmpty()) {
            return registry.all();
        }
        Set<String> ids = new HashSet<>(req.engineIds());
        return registry.all().stream().filter(e -> ids.contains(e.id())).toList();
    }

    private static String likePattern(String substring) {
        return substring.contains("%") ? substring : "%" + substring + "%";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    @PreDestroy
    void shutdown() {
        fanout.shutdownNow();
    }
}
