package io.inspector.aggregate;

import io.inspector.api.MdcPropagatingExecutors;
import io.inspector.audit.ProtectedInstance;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessApiClient.JobLaneKind;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.detail.InstanceDetailService;
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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
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
    private final ProcessApiClient flowable;
    private final InspectorProperties props;
    private final ProtectedInstanceRepository protectedInstances;
    private final ExecutorService fanout = MdcPropagatingExecutors.newVirtualThreadPerTaskExecutor();
    // Package-private for the DEEP_PAGE-isolation test (F3 / R-NFR-08).
    final Semaphore engineSlots;
    final Semaphore deepPageSlots;

    public SearchService(
            EngineRegistry registry,
            ProcessApiClient flowable,
            InspectorProperties props,
            ProtectedInstanceRepository protectedInstances) {
        this.registry = registry;
        this.flowable = flowable;
        this.props = props;
        this.protectedInstances = protectedInstances;
        int fanoutN = props.fanoutParallelism() != null ? props.fanoutParallelism() : 8;
        this.engineSlots = new Semaphore(fanoutN);
        // F3 (R-NFR-08): deep paging gets its OWN, smaller BFF fan-out budget. The engine-side
        // Resilience4j DEEP_PAGE bulkhead only throttles AFTER a BFF slot is taken, so sharing
        // engineSlots let a scroller (or a crafted-cursor flood) occupy the interactive slots and
        // starve live search — the exact opposite of the do-no-harm claim below. A separate,
        // smaller semaphore means deep scroll queues on THESE permits and never touches interactive.
        this.deepPageSlots = new Semaphore(Math.max(1, fanoutN / 2));
    }

    public SearchResponse search(SearchRequest request) {
        return aggregate(request, null, null);
    }

    /**
     * Scope-filtered interactive search (S2, R-SAFE-17). {@code readableEngineIds} is the set of
     * engines the caller may read, or {@code null} = unrestricted (enforcement off — the legacy
     * fleet-wide behaviour). Only the interactive read path is scoped; {@link #resolveAllMatching}
     * (bulk enumeration) stays unfiltered — bulk is gated at submit and per item.
     */
    public SearchResponse search(SearchRequest request, Set<String> readableEngineIds) {
        return aggregate(request, null, readableEngineIds);
    }

    /**
     * Filter-bulk resolution (v1.x #2, SPEC §7): the SAME per-engine plan as {@link #search},
     * but paged to exhaustion so the returned rows are the COMPLETE match list — never one
     * grid page impersonating "all matching". {@code rowCap} bounds the harvest: a slice
     * stops collecting at {@code rowCap + 1} rows (the caller detects overflow and refuses),
     * and a MIXED-plan candidate pool that is already known to exceed the cap degrades that
     * engine honestly instead of enumerating it.
     */
    public SearchResponse resolveAllMatching(SearchRequest request, int rowCap) {
        return aggregate(request, rowCap, null);
    }

    /** A deep-paging cursor older than this is rejected (bounds replay + incoherent-merge window). */
    private static final long CURSOR_TTL_MILLIS =
            java.time.Duration.ofMinutes(10).toMillis();
    /** Default GLOBAL rows per deep page when the caller sends none; clamped by {@link #MAX_DEEP_PAGE_SIZE}. */
    private static final int DEFAULT_DEEP_PAGE_SIZE = 50;

    private static final int MAX_DEEP_PAGE_SIZE = 200;

    /**
     * One deep page of the globally-sorted merged stream (R-SEM-22 / R-NFR-08,
     * {@code docs/KWAY-PAGING.md}). MIXED / {@code startTime desc} ONLY: the cursor resumes on a
     * per-engine startTime offset, so a {@code failureTime} sort (BFF-derived, no engine resume)
     * and a FAILED/RETRYING-only (INVERTED) search are both refused here — the caller surfaces the
     * depth-wall time-filter seam instead. The fan-out runs on a dedicated BFF slot budget
     * ({@link #deepPageSlots}) AND the engine-side {@code DEEP_PAGE} Resilience4j lane, so a scroller
     * (or a crafted-cursor flood) degrades itself and never starves interactive search's slots.
     * A crafted cursor is bound-checked ({@link PagingCursor#validateInbound}) BEFORE any engine is
     * touched — that inbound check, not the {@code filterHash}, is the DoS ceiling.
     *
     * @param cursorToken the opaque cursor from the previous page, or blank/null for the first page
     * @param nowMillis   the wall clock for the TTL check and the emitted cursor's {@code issuedAt}
     */
    public DeepPage deepPage(SearchRequest request, String cursorToken, long nowMillis) {
        return deepPage(request, cursorToken, nowMillis, null);
    }

    /** Scope-filtered "Load more" (S2, R-SAFE-17): {@code readableEngineIds} null = unrestricted. */
    public DeepPage deepPage(SearchRequest request, String cursorToken, long nowMillis, Set<String> readableEngineIds) {
        BffFilters bff = BffFilters.of(request); // validates the failure window → 400 on bad ISO
        if (request.definitionVersion() != null && !notBlank(request.processDefinitionKey())) {
            throw new IllegalArgumentException("definitionVersion requires processDefinitionKey");
        }
        String sortBy = notBlank(request.sortBy()) ? request.sortBy() : "startTime";
        if (!"startTime".equals(sortBy)) {
            throw new IllegalArgumentException(
                    "deep paging is available only for startTime-ordered searches — narrow by a failure-time window instead");
        }
        Set<InstanceStatus> wanted = EnumSet.copyOf(request.effectiveStatuses());
        if (StatusJoin.planFor(wanted) == StatusJoin.Plan.INVERTED) {
            throw new IllegalArgumentException(
                    "deep paging is not available for FAILED/RETRYING-only searches — narrow by a failure-time window instead");
        }

        List<EngineConfig> targets = resolveTargets(request, readableEngineIds);
        Map<String, Integer> depthCaps = new HashMap<>();
        targets.forEach(e -> depthCaps.put(e.id(), e.deepPagingMaxDepthOrDefault()));

        String filterHash = PagingCursor.filterHash(request);
        final PagingCursor incoming;
        if (notBlank(cursorToken)) {
            incoming = PagingCursor.decode(cursorToken); // 400 on garbage
            incoming.validateInbound(filterHash, sortBy, depthCaps, CURSOR_TTL_MILLIS, nowMillis); // 400 pre-fan-out
        } else {
            incoming = null;
        }

        int globalPageSize = Math.min(
                request.pageSize() != null && request.pageSize() > 0 ? request.pageSize() : DEFAULT_DEEP_PAGE_SIZE,
                MAX_DEEP_PAGE_SIZE);

        Map<EngineConfig, CompletableFuture<EngineSlice>> futures = new LinkedHashMap<>();
        for (EngineConfig engine : targets) {
            int offset = incoming != null ? incoming.offsets().getOrDefault(engine.id(), 0) : 0;
            PageWindow window = new PageWindow(offset, Math.min(globalPageSize, engine.maxPageSizeOrDefault()));
            futures.put(
                    engine,
                    CompletableFuture.supplyAsync(
                            () -> {
                                deepPageSlots.acquireUninterruptibly();
                                try {
                                    return searchOneEngine(
                                            engine, request, wanted, bff, null, window, CallPriority.DEEP_PAGE);
                                } finally {
                                    deepPageSlots.release();
                                }
                            },
                            fanout));
        }

        Map<String, EngineResult> perEngine = new ConcurrentHashMap<>();
        markExcludedEngines(request, targets, perEngine);
        Map<String, List<ProcessInstanceRow>> emittable = new LinkedHashMap<>();
        Map<String, List<String>> rawKeys = new LinkedHashMap<>();
        Map<String, Long> totals = new LinkedHashMap<>();
        for (Map.Entry<EngineConfig, CompletableFuture<EngineSlice>> e : futures.entrySet()) {
            EngineConfig engine = e.getKey();
            long budgetMs = engine.timeoutsOrDefault().read() * 6L + 2000;
            try {
                EngineSlice slice = e.getValue().get(budgetMs, TimeUnit.MILLISECONDS);
                emittable.put(engine.id(), slice.rows());
                rawKeys.put(engine.id(), slice.rawWindowStartKeys());
                totals.put(engine.id(), slice.total());
                perEngine.put(
                        engine.id(),
                        EngineResult.success(slice.rows().size(), slice.total(), slice.dlqScan(), slice.failingScan()));
            } catch (TimeoutException te) {
                e.getValue().cancel(true);
                perEngine.put(engine.id(), EngineResult.failure("timeout after " + budgetMs + "ms"));
            } catch (Exception ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                log.warn("Engine {} deep page failed: {}", engine.id(), cause.toString());
                perEngine.put(engine.id(), EngineResult.failure(cause.getMessage()));
            }
        }

        PagingCursor.PageResult merged = PagingCursor.mergePage(
                incoming, emittable, rawKeys, totals, globalPageSize, sortBy, filterHash, depthCaps, nowMillis);
        List<ProcessInstanceRow> rows = markProtected(new ArrayList<>(merged.rows()));
        String nextCursor = merged.nextCursor() != null ? merged.nextCursor().encode() : null;
        // #273: decorate the walled engines' OWN envelope so the client can tell "this lane is
        // permanently frozen below its total" apart from "this lane just has more to fetch".
        for (String eng : merged.cappedEngines()) {
            perEngine.computeIfPresent(eng, (id, r) -> r.withCapped(true));
        }
        return new DeepPage(rows, new HashMap<>(perEngine), nextCursor, merged.depthCapped());
    }

    private SearchResponse aggregate(SearchRequest request, Integer exhaustCap, Set<String> readableEngineIds) {
        BffFilters bff = BffFilters.of(request); // validates the failure window → 400 on bad ISO
        if (request.definitionVersion() != null && !notBlank(request.processDefinitionKey())) {
            // A bare version number is meaningless across definitions — the version-drill
            // (triage cards, SPEC §8) always carries its key.
            throw new IllegalArgumentException("definitionVersion requires processDefinitionKey");
        }
        String sortBy = notBlank(request.sortBy()) ? request.sortBy() : "startTime";
        if (!sortBy.equals("startTime") && !sortBy.equals("failureTime")) {
            throw new IllegalArgumentException("sortBy must be 'startTime' or 'failureTime', got '" + sortBy + "'");
        }
        List<EngineConfig> targets = resolveTargets(request, readableEngineIds);
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
        markExcludedEngines(request, targets, perEngine);

        Map<EngineConfig, CompletableFuture<EngineSlice>> futures = new LinkedHashMap<>();
        for (EngineConfig engine : targets) {
            futures.put(
                    engine,
                    CompletableFuture.supplyAsync(
                            () -> {
                                engineSlots.acquireUninterruptibly();
                                try {
                                    return searchOneEngine(
                                            engine, request, wanted, bff, exhaustCap, null, CallPriority.INTERACTIVE);
                                } finally {
                                    engineSlots.release();
                                }
                            },
                            fanout));
        }

        List<ProcessInstanceRow> rows = new ArrayList<>();
        Map<InstanceStatus, Long> statusCounts = new EnumMap<>(InstanceStatus.class);
        // Per-engine page-1 material for the deep-paging ENTRY cursor (below): the emittable rows,
        // the RAW window keys (offset-advance basis), and the totals (overflow detection).
        Map<String, List<ProcessInstanceRow>> emittableByEngine = new LinkedHashMap<>();
        Map<String, List<String>> rawKeysByEngine = new LinkedHashMap<>();
        Map<String, Long> totalsByEngine = new LinkedHashMap<>();
        for (Map.Entry<EngineConfig, CompletableFuture<EngineSlice>> e : futures.entrySet()) {
            EngineConfig engine = e.getKey();
            // Generous outer guard; the real limits are the per-call read timeout, the
            // per-engine breaker and the bounded scans inside the plan. Exhaustive
            // resolution pages up to cap/pageSize times, so the backstop scales with it.
            long budgetMs = engine.timeoutsOrDefault().read() * 6L + 2000;
            if (exhaustCap != null) {
                budgetMs *= Math.max(1, exhaustCap / engine.maxPageSizeOrDefault());
            }
            try {
                EngineSlice slice = e.getValue().get(budgetMs, TimeUnit.MILLISECONDS);
                rows.addAll(slice.rows());
                slice.counts().forEach((status, n) -> statusCounts.merge(status, n, Long::sum));
                emittableByEngine.put(engine.id(), slice.rows());
                rawKeysByEngine.put(engine.id(), slice.rawWindowStartKeys());
                totalsByEngine.put(engine.id(), slice.total());
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

        // R-SEM-23: a deterministic TOTAL order — the sort key (parsed as an Instant so the
        // offset-form vs Z-form ISO strings different engines emit collate correctly) then
        // compositeId as the final tiebreak, so a given merged set orders identically across
        // requests. Extracted to StatusJoin (rung-1 tested); the old inline branch compared
        // startTime as a raw String with no tiebreak (nondeterministic among same-second ties).
        rows.sort(StatusJoin.resultOrder(sortBy));

        // Deep-paging ENTRY cursor (R-SEM-22, docs/KWAY-PAGING.md): the frontend cannot mint the
        // opaque cursor itself, so an ordinary MIXED / startTime-desc search whose result OVERFLOWS
        // (some engine has total > fetched) hands back the cursor that resumes AFTER this page —
        // the "Load more" entry point. mergePage(null, …, pageSize = all shown rows) emits exactly
        // the page-1 rows and computes that resume position. ~95% of searches never overflow, so
        // this stays null and the cursor machinery is untouched.
        String nextCursor = null;
        boolean depthCapped = false;
        if (exhaustCap == null
                && "startTime".equals(sortBy)
                && StatusJoin.planFor(wanted) == StatusJoin.Plan.MIXED
                && !rows.isEmpty()
                && perEngine.values().stream().anyMatch(r -> r.ok() && r.total() > r.fetched())) {
            Map<String, Integer> depthCaps = new HashMap<>();
            targets.forEach(t -> depthCaps.put(t.id(), t.deepPagingMaxDepthOrDefault()));
            PagingCursor.PageResult entry = PagingCursor.mergePage(
                    null,
                    emittableByEngine,
                    rawKeysByEngine,
                    totalsByEngine,
                    rows.size(),
                    sortBy,
                    PagingCursor.filterHash(request),
                    depthCaps,
                    System.currentTimeMillis());
            depthCapped = entry.depthCapped();
            if (entry.nextCursor() != null) nextCursor = entry.nextCursor().encode();
            // #273: same per-lane decoration as deepPage() — page 1 can already wall an engine
            // (a tiny configured depth cap, or a pathological cap of 0).
            for (String eng : entry.cappedEngines()) {
                perEngine.computeIfPresent(eng, (id, r) -> r.withCapped(true));
            }
        }
        return new SearchResponse(
                markProtected(rows), new HashMap<>(perEngine), statusCounts, null, null, nextCursor, depthCapped, null);
    }

    /**
     * R-SAFE-05 enrichment for the bulk bar: one batched lookup against the protected
     * registry per result page. A Postgres outage leaves the flag {@code null} (unknown)
     * — search must not fail over it; the execution-time guard still refuses fail-closed.
     */
    private List<ProcessInstanceRow> markProtected(List<ProcessInstanceRow> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Set<ProtectedInstance.Key> guarded;
        try {
            List<ProtectedInstance.Key> keys = rows.stream()
                    .map(row -> new ProtectedInstance.Key(row.engineId(), row.processInstanceId()))
                    .toList();
            guarded = protectedInstances.findAllById(keys).stream()
                    .map(p -> new ProtectedInstance.Key(p.getEngineId(), p.getInstanceId()))
                    .collect(java.util.stream.Collectors.toSet());
        } catch (RuntimeException e) {
            log.warn("protected-instance lookup unavailable — rows carry protectedInstance=null: {}", e.toString());
            return rows;
        }
        return rows.stream()
                .map(row -> row.withProtected(
                        guarded.contains(new ProtectedInstance.Key(row.engineId(), row.processInstanceId()))))
                .toList();
    }

    /**
     * One engine's contribution: filtered rows, honesty markers, the facet counts, and — for a
     * deep-paging window only — the sort-keys of EVERY raw engine row in the window (including
     * ones the BFF filtered out), so the cursor advances its offset over the engine's RAW result
     * set, not the filtered subset. {@code null}/empty on the normal (non-deep-paged) path.
     */
    private record EngineSlice(
            List<ProcessInstanceRow> rows,
            long total,
            String dlqScan,
            String failingScan,
            Map<InstanceStatus, Long> counts,
            List<String> rawWindowStartKeys) {}

    /** One bounded page fetched from one engine at a given offset — the deep-paging fetch unit. */
    private record PageWindow(int start, int size) {}

    /**
     * One deep page's result, before it is mapped onto the API {@code SearchResponse} (S3):
     * the merged/emitted rows, the per-engine reachability envelope, the opaque {@code nextCursor}
     * (null at end of stream), and whether any engine reached its depth cap.
     */
    public record DeepPage(
            List<ProcessInstanceRow> rows,
            Map<String, EngineResult> perEngine,
            String nextCursor,
            boolean depthCapped) {}

    /**
     * The parsed BFF-side filters (M2b, SPEC §8): the failure window + error text apply to
     * job rows on the scan legs (Flowable cannot query instances by dead-letter evidence);
     * currentActivity is matched against unfinished activity id/name per candidate row.
     */
    private record BffFilters(
            Instant failedAfter, Instant failedBefore, String errorText, String signatureHash, String currentActivity) {
        static BffFilters of(SearchRequest req) {
            return new BffFilters(
                    parseBound(req.failureTimeAfter(), "failureTimeAfter"),
                    parseBound(req.failureTimeBefore(), "failureTimeBefore"),
                    notBlank(req.errorText()) ? req.errorText() : null,
                    notBlank(req.signatureHash()) ? req.signatureHash() : null,
                    notBlank(req.currentActivity()) ? req.currentActivity() : null);
        }

        boolean anyFailureFilter() {
            return failedAfter != null || failedBefore != null || errorText != null || signatureHash != null;
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
                rows.add(row(engine, pi, flags, failure));
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
                rows.add(row(engine, pi, flags, failure));
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

    /* ================= row assembly & query bodies ================= */

    // Package-private (not private) for SearchServiceRowTest — direct rung-1 coverage of the
    // row-assembly mapping, same testability seam as InstanceDetailService.terminationReason.
    ProcessInstanceRow row(EngineConfig engine, Map<String, Object> pi, InstanceStatusFlags flags, Failure failure) {
        String id = str(pi, "id");
        String definitionId = str(pi, "processDefinitionId");
        return new ProcessInstanceRow(
                engine.id() + ":" + id,
                engine.id(),
                engine.name(),
                engine.accentColor(),
                id,
                str(pi, "businessKey"),
                // Root-vs-child (W2 #7, R-UXQ-12): historic rows serialize the parent id for
                // call-activity children; null = root. Both plan legs assemble from historic rows.
                str(pi, "superProcessInstanceId"),
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
                failure != null ? failure.snippet() : null,
                null, // protectedInstance filled by markProtected() on the final page
                InstanceDetailService.terminationReason(pi, flags.ended()));
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

    private List<EngineConfig> resolveTargets(SearchRequest req, Set<String> readableEngineIds) {
        List<EngineConfig> base;
        if (req.engineIds() == null || req.engineIds().isEmpty()) {
            base = registry.all();
        } else {
            Set<String> ids = new HashSet<>(req.engineIds());
            base = registry.all().stream().filter(e -> ids.contains(e.id())).toList();
        }
        // S2 (R-SAFE-17): when read-scope enforcement is on, the caller may only fan out over engines
        // its grants overlap at VIEWER. null = unrestricted (enforcement off) — never treated as empty.
        if (readableEngineIds == null) {
            return base;
        }
        return base.stream().filter(e -> readableEngineIds.contains(e.id())).toList();
    }

    /**
     * Mark each EXPLICITLY-requested engine that will not be queried as a labeled excluded leg on the
     * perEngine envelope (never silently dropped, R-SEM-24): de-registered, disabled, or — under S2
     * read scoping — outside the caller's access scope. Only explicit {@code engineIds} are labeled;
     * an implicit "all engines" request narrows silently to the readable set, because labeling every
     * unreadable engine would leak the existence and ids of engines the caller may not see. An
     * enabled, still-resolvable engine absent from {@code targets} can only have been scope-excluded
     * (the disabled/removed cases are not enabled+resolvable), so the classification is exact.
     */
    private void markExcludedEngines(
            SearchRequest request, List<EngineConfig> targets, Map<String, EngineResult> perEngine) {
        if (request.engineIds() == null || request.engineIds().isEmpty()) {
            return;
        }
        Set<String> resolved = new HashSet<>();
        for (EngineConfig t : targets) {
            resolved.add(t.id());
        }
        for (String requested : request.engineIds()) {
            if (requested == null || requested.isBlank() || resolved.contains(requested)) {
                continue;
            }
            Optional<EngineConfig> row = registry.resolve(requested);
            String why;
            if (row.isPresent() && row.get().enabled()) {
                why = "the engine \"" + requested + "\" is outside your access scope — excluded from this search";
            } else if (row.isPresent()) {
                why = "the engine \"" + requested + "\" is currently disabled — excluded from this search";
            } else {
                why = "the engine \"" + requested + "\" is no longer registered — excluded from this search";
            }
            perEngine.put(requested, EngineResult.failure(why));
        }
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
