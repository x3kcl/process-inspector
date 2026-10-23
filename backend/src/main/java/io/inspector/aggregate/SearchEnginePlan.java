package io.inspector.aggregate;

import static io.inspector.aggregate.SearchService.likePattern;
import static io.inspector.aggregate.SearchService.notBlank;
import static io.inspector.aggregate.SearchService.str;

import io.inspector.aggregate.SearchService.BffFilters;
import io.inspector.aggregate.SearchService.EngineSlice;
import io.inspector.aggregate.SearchService.Failure;
import io.inspector.aggregate.SearchService.PageWindow;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessApiClient.JobLaneKind;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.InstanceStatusFlags;
import io.inspector.dto.ProcessInstanceRow;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Per-engine INVERTED and MIXED plans. Moved from {@link SearchService} (issue #475). */
final class SearchEnginePlan {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final ProcessApiClient flowable;
    private final InspectorProperties props;
    private final ExecutorService fanout;
    private final SearchService search;

    SearchEnginePlan(
            ProcessApiClient flowable, InspectorProperties props, ExecutorService fanout, SearchService search) {
        this.flowable = flowable;
        this.props = props;
        this.fanout = fanout;
        this.search = search;
    }

    EngineSlice searchOneEngine(
            EngineConfig engine,
            SearchRequest req,
            Set<InstanceStatus> wanted,
            BffFilters bff,
            Integer exhaustCap,
            PageWindow window,
            CallPriority priority) {
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
        // Definition filter resolved ONCE per engine to concrete per-version ids (scan-leg
        // pushdown, ARCH §2.3). Null = unfiltered; an empty resolution — key not deployed
        // here, or the requested definitionVersion doesn't exist — is an honestly empty
        // slice, never an unfiltered scan.
        List<String> definitionIds = null;
        if (notBlank(req.processDefinitionKey())) {
            definitionIds = resolveDefinitionIds(engine, req.processDefinitionKey(), req.definitionVersion(), priority);
            if (definitionIds.isEmpty()) {
                return new EngineSlice(List.of(), 0, null, null, Map.of(), List.of());
            }
        }
        List<String> defIds = definitionIds;
        return switch (StatusJoin.planFor(wanted)) {
            case INVERTED -> invertedPlan(engine, req, wanted, pageSize, bff, defIds, exhaustCap, priority);
            case MIXED -> mixedPlan(engine, req, wanted, pageSize, bff, defIds, exhaustCap, window, priority);
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
        return flowable.queryHistoricProcessInstances(engine, CallPriority.INTERACTIVE, canary)
                        .total()
                > 0;
    }

    /* ================= INVERTED plan — drive FROM the job queues ================= */

    private EngineSlice invertedPlan(
            EngineConfig engine,
            SearchRequest req,
            Set<InstanceStatus> wanted,
            int pageSize,
            BffFilters bff,
            List<String> definitionIds,
            Integer exhaustCap,
            CallPriority priority) {
        LaneScan dlq = wanted.contains(InstanceStatus.FAILED)
                ? scanLane(engine, JobLaneKind.DEADLETTER, Map.of(), definitionIds, bff, priority)
                : LaneScan.empty();
        LaneScan failing = wanted.contains(InstanceStatus.RETRYING)
                ? scanExceptionLanes(engine, definitionIds, bff, priority)
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
            return new EngineSlice(List.of(), 0, dlq.marker(), failing.marker(), Map.of(), List.of());
        }

        // Hydration: the historic query carrying the id set plus the remaining filters —
        // the engine (not the BFF) evaluates businessKey/time/variable predicates. The grid
        // read takes ONE page; exhaustive resolution (v1.x #2) pages the same query until
        // the id set is fully hydrated or the harvest overflows the cap.
        List<ProcessInstanceRow> rows = new ArrayList<>();
        Map<InstanceStatus, Long> counts = new EnumMap<>(InstanceStatus.class);
        long total = 0;
        int start = 0;
        while (true) {
            Map<String, Object> body = historicBody(engine, req, pageSize, definitionIds);
            body.put("processInstanceIds", List.copyOf(ids));
            if (start > 0) body.put("start", start);
            FlowablePage page = flowable.queryHistoricProcessInstances(engine, priority, body);
            List<Map<String, Object>> data = page.dataOrEmpty();
            total = page.total();

            Map<String, Boolean> suspendedById = suspendedFlags(engine, openIds(data), priority);
            Set<String> activityMatched = activityMatches(engine, openIds(data), bff, priority);

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
                Failure failure =
                        dlqByInstance.getOrDefault(id, rollupFailure.getOrDefault(id, failingByInstance.get(id)));
                rows.add(search.row(engine, pi, flags, failure));
            }
            start += data.size();
            if (exhaustCap == null || data.isEmpty() || start >= total || rows.size() > exhaustCap) break;
        }
        return new EngineSlice(rows, total, dlq.marker(), failing.marker(), counts, List.of());
    }

    /* ================= MIXED plan — historic first, enrich the page ================= */

    private EngineSlice mixedPlan(
            EngineConfig engine,
            SearchRequest req,
            Set<InstanceStatus> wanted,
            int pageSize,
            BffFilters bff,
            List<String> definitionIds,
            Integer exhaustCap,
            PageWindow window,
            CallPriority priority) {
        // RETRYING tier: two capped lane scans → membership set (the failing lanes are
        // small). Scanned ONCE — the exhaustive loop below must not repeat it per page.
        LaneScan failing = scanExceptionLanes(engine, definitionIds, bff, priority);
        Map<String, Failure> failingByInstance = indexByInstance(applyFailureFilters(failing.jobs(), bff));

        List<ProcessInstanceRow> rows = new ArrayList<>();
        Map<InstanceStatus, Long> counts = new EnumMap<>(InstanceStatus.class);
        // The raw sort-keys of EVERY row the engine returns (pre BFF-filter) feed the deep-paging
        // cursor's offset advance — collected for the deep-page window AND for a normal page-1 search
        // (which is a single page here, since exhaustCap is null → one loop iteration) so the ENTRY
        // cursor can resume after it. Unused (but harmless) on the exhaustive filter-bulk path.
        List<String> rawWindowStartKeys = new ArrayList<>();
        long total = 0;
        int start = window != null ? window.start() : 0;
        while (true) {
            Map<String, Object> body = historicBody(engine, req, pageSize, definitionIds);
            // Narrow finished when the status OR-set is one-sided (pure optimization).
            boolean wantsOpen = wanted.stream().anyMatch(s -> s != InstanceStatus.COMPLETED);
            boolean wantsCompleted = wanted.contains(InstanceStatus.COMPLETED);
            if (wantsCompleted && !wantsOpen) body.put("finished", true);
            else if (wantsOpen && !wantsCompleted) body.put("finished", false);
            if (start > 0) body.put("start", start);

            FlowablePage historic = flowable.queryHistoricProcessInstances(engine, priority, body);
            List<Map<String, Object>> data = historic.dataOrEmpty();
            total = historic.total();
            if (exhaustCap != null && total > exhaustCap) {
                // Exhaustive resolution over a candidate pool this size would N+1-enrich
                // every row — refuse honestly instead of enumerating a subset (v1.x #2).
                throw new IllegalStateException("the criteria match " + total
                        + " candidate instances on this engine — over the " + exhaustCap
                        + "-item filter-bulk cap; narrow the filter");
            }
            for (Map<String, Object> pi : data) rawWindowStartKeys.add(str(pi, "startTime"));
            Set<String> open = openIds(data);

            Map<String, Boolean> suspendedById = suspendedFlags(engine, open, priority);
            // DLQ membership per candidate OPEN row — the sanctioned bounded N+1 over one
            // page (ARCH §2.3), parallelized on virtual threads, throttled by the engine
            // bulkhead.
            Map<String, Failure> dlqByInstance = deadLetterMembership(engine, open, bff, priority);
            Set<String> activityMatched = activityMatches(engine, open, bff, priority);

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
                rows.add(search.row(engine, pi, flags, failure));
            }
            start += data.size();
            // A deep-paging window is exactly ONE page (exhaustCap is null there → the same break).
            if (exhaustCap == null || data.isEmpty() || start >= total || rows.size() > exhaustCap) break;
        }
        return new EngineSlice(rows, total, null, failing.marker(), counts, rawWindowStartKeys);
    }

    /**
     * The current-activity leg (SPEC §8: "activity id/name contains"): unfinished historic
     * activities per candidate OPEN row — a bounded N+1 like the DLQ membership check, on
     * virtual threads behind the engine bulkhead. Null = filter absent (no restriction);
     * completed rows can never match (they have no unfinished activities).
     */
    private Set<String> activityMatches(
            EngineConfig engine, Set<String> openIds, BffFilters bff, CallPriority priority) {
        if (bff.currentActivity() == null) return null;
        if (openIds.isEmpty()) return Set.of();
        String needle = bff.currentActivity().toLowerCase(Locale.ROOT);
        Map<String, Boolean> hits = parallelPerId(openIds, id -> {
            for (Map<String, Object> activity : flowable.listUnfinishedActivities(
                            engine, priority, id, engine.tenantId(), engine.maxPageSizeOrDefault())
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
    private LaneScan scanExceptionLanes(
            EngineConfig engine, List<String> definitionIds, BffFilters bff, CallPriority priority) {
        Map<String, String> withException = Map.of("withException", "true");
        return scanLane(engine, JobLaneKind.EXECUTABLE, withException, definitionIds, bff, priority)
                .and(scanLane(engine, JobLaneKind.TIMER, withException, definitionIds, bff, priority));
    }

    /**
     * Bounded exhaustive paging of one job lane — NEVER a single unpaged fetch (default page
     * size is 10; anything past it would silently declassify FAILED → ACTIVE). A definition
     * filter arrives pre-resolved as concrete per-version {@code processDefinitionId}s (null =
     * unfiltered). The signature drill-down bites here, where the lane is known — the
     * refinement bridge needs it for the representative stacktrace fetch.
     */
    private LaneScan scanLane(
            EngineConfig engine,
            JobLaneKind lane,
            Map<String, String> baseFilters,
            List<String> resolvedDefinitionIds,
            BffFilters bff,
            CallPriority priority) {
        int cap = engine.dlqScanCapOrDefault();
        int pageSize = engine.maxPageSizeOrDefault();
        List<String> definitionIds =
                resolvedDefinitionIds == null ? java.util.Collections.singletonList(null) : resolvedDefinitionIds;

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
                FlowablePage page = flowable.listJobs(engine, priority, lane, filters, start, size);
                List<Map<String, Object>> batch = page.dataOrEmpty();
                scanned += batch.size();
                jobs.addAll(batch);
                start += batch.size();
                if (batch.isEmpty() || start >= page.total()) break; // lane exhausted
            }
            if (truncated) break;
        }
        return new LaneScan(applySignatureFilter(jobs, bff), truncated, scanned);
    }

    /**
     * The signature drill-down over one lane's job rows (SPEC §8, R-SEM-03), delegating to the
     * pure matcher ({@link StatusJoin#filterBySignatureHash}).
     *
     * <p>Algo v2 (#270) made group identity snippet-only, so this no longer injects a
     * representative-stacktrace fetch or spends the sample cap: the job rows already carry
     * everything the hash is computed from. One fewer engine call per drilled lane, and the
     * drill can no longer come back empty for a card that is currently failing.
     */
    private static List<Map<String, Object>> applySignatureFilter(List<Map<String, Object>> jobs, BffFilters bff) {
        if (bff.signatureHash() == null || jobs.isEmpty()) return jobs;
        return StatusJoin.filterBySignatureHash(jobs, bff.signatureHash());
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
    private Map<String, Boolean> suspendedFlags(EngineConfig engine, Set<String> ids, CallPriority priority) {
        if (ids.isEmpty()) return Map.of();
        Map<String, Object> body = new HashMap<>();
        body.put("processInstanceIds", List.copyOf(ids));
        body.put("size", ids.size());
        tenant(engine).ifPresent(t -> body.put("tenantId", t));
        FlowablePage page = flowable.queryRuntimeProcessInstances(engine, priority, body);

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
            Map<String, Object> pi = flowable.getRuntimeProcessInstance(engine, priority, id);
            return pi != null && Boolean.TRUE.equals(pi.get("suspended"));
        });
    }

    /** Bounded N+1 DLQ membership for one page of open rows, parallel on virtual threads. */
    private Map<String, Failure> deadLetterMembership(
            EngineConfig engine, Set<String> ids, BffFilters bff, CallPriority priority) {
        if (ids.isEmpty()) return Map.of();
        Map<String, Failure> result = new ConcurrentHashMap<>();
        parallelPerId(ids, id -> {
            Map<String, String> filters = new HashMap<>();
            filters.put("processInstanceId", id);
            tenant(engine).ifPresent(t -> filters.put("tenantId", t));
            FlowablePage page = flowable.listJobs(
                    engine, priority, JobLaneKind.DEADLETTER, filters, 0, engine.maxPageSizeOrDefault());
            // Same failure-window/error-text/signature semantics as the inverted plan's scan legs.
            Map<String, Failure> perInstance =
                    indexByInstance(applyFailureFilters(applySignatureFilter(page.dataOrEmpty(), bff), bff));
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
                        for (Map<String, Object> row : flowable.queryHistoricProcessInstances(
                                        engine, CallPriority.INTERACTIVE, body)
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

    /**
     * Shared scalar/variable filters (AND semantics, SPEC §3.A). No engine serializes
     * processDefinitionKey/version on historic rows across the version matrix — both are
     * derived from processDefinitionId ({@code key:version:uuid}).
     */
    private Map<String, Object> historicBody(
            EngineConfig engine, SearchRequest req, int pageSize, List<String> definitionIds) {
        Map<String, Object> body = new HashMap<>();
        if (req.definitionVersion() != null && definitionIds != null && !definitionIds.isEmpty()) {
            // key+version resolve to exactly one deployed definition per engine — push the
            // concrete id down (processDefinitionVersion is not queryable across the matrix).
            body.put("processDefinitionId", definitionIds.get(0));
        } else if (notBlank(req.processDefinitionKey())) {
            body.put("processDefinitionKey", req.processDefinitionKey());
        }
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

    /**
     * Concrete deployed definition ids for a key, optionally narrowed to ONE exact version.
     * Paged to exhaustion: a long-deployed key accumulates more versions than one engine
     * page holds, and a version outside the first page must not silently vanish from the
     * scan-leg pushdown (it would return an honestly-WRONG empty slice).
     */
    private List<String> resolveDefinitionIds(EngineConfig engine, String key, Integer version, CallPriority priority) {
        List<String> ids = new ArrayList<>();
        int pageSize = engine.maxPageSizeOrDefault();
        int start = 0;
        while (true) {
            FlowablePage page = flowable.listProcessDefinitionsByKey(engine, priority, key, version, start, pageSize);
            List<Map<String, Object>> data = page.dataOrEmpty();
            for (Map<String, Object> def : data) {
                String id = str(def, "id");
                if (id != null) ids.add(id);
            }
            start += data.size();
            if (data.isEmpty() || start >= page.total()) {
                return ids;
            }
        }
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
}
