package io.inspector.bulk;

import io.inspector.action.ActionRequest;
import io.inspector.action.ActionResult;
import io.inspector.action.ActionVerb;
import io.inspector.action.CasConflictException;
import io.inspector.action.CorrectiveActionService;
import io.inspector.action.EngineRejectedException;
import io.inspector.action.GuardRefusedException;
import io.inspector.action.OutcomeUnknownException;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditOutcome;
import io.inspector.audit.AuditService;
import io.inspector.audit.OutcomeVerificationFailedException;
import io.inspector.audit.ProtectedInstance;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.FlowableEngineClient;
import io.inspector.client.FlowableEngineClient.JobLaneKind;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.reauth.DangerousActionReauthGate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * M5 grid-selection bulk (SPEC §7, R-SEM-10/11): a PERSISTED tracked job — submit
 * records the resolved target list BEFORE acting, then a per-item fan-out where every
 * item runs the FULL single-target guard chain ({@link CorrectiveActionService}: RBAC,
 * engine gates, protected guard, server-fresh restatement, fail-closed per-item audit).
 * No cross-engine transaction pretense; partial failure is a NORMAL outcome.
 *
 * <p>Engine protection (v1.x #2, SPEC §7): dispatch fans out per ENGINE — each engine's
 * items are paced by a mandatory stagger between dispatch STARTS and bounded by a
 * per-engine permit pool shared across concurrent jobs ({@code inspector.bulk.*}), so a
 * 5000-item filter bulk trickles into the target async executor instead of slamming it.
 * The pacer sleeps on its own virtual thread — cheap and non-carrier-blocking. retry-job
 * resolves the instance's CURRENT dead-letter jobs at dispatch time (the built-in
 * precondition recheck — none left ⇒ {@code skipped (already resolved)}). A timed-out
 * mutation is {@code unknown} and is NEVER auto-retried; Verify-now (R-SAFE-09) re-runs
 * the verb's precondition predicate and reclassifies with evidence.
 *
 * <p>Every observable transition publishes an id-only {@link BulkJobChangedEvent} — the
 * SSE bridge's feed (live-ui-sse doctrine: push the signal, the browser refetches).
 */
@Service
public class BulkJobService {

    private static final Logger log = LoggerFactory.getLogger(BulkJobService.class);

    /** SPEC §7 v1 verb whitelist: queue-state verbs only — destructive bulk is the tier-4 wizard (deferred). */
    private static final Set<ActionVerb> BULK_VERBS =
            Set.of(ActionVerb.RETRY_JOB, ActionVerb.SUSPEND, ActionVerb.ACTIVATE, ActionVerb.TRIGGER_TIMER);

    private static final int DLQ_JOBS_PER_INSTANCE_CAP = 20;

    private final BulkJobRepository jobs;
    private final BulkJobItemRepository items;
    private final CorrectiveActionService actions;
    private final ProtectedInstanceRepository protectedInstances;
    private final AuditService audit;
    private final EngineRegistry registry;
    private final FlowableEngineClient client;
    private final Clock clock;
    private final InspectorProperties props;
    private final ApplicationEventPublisher events;
    private final DangerousActionReauthGate reauth;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, Boolean> cancelRequested = new ConcurrentHashMap<>();
    /** Per-engine in-flight dispatch permits, shared across concurrent jobs (SPEC §7). */
    private final Map<String, Semaphore> enginePermits = new ConcurrentHashMap<>();
    /**
     * Jobs whose dispatch to an engine PAUSED on an open circuit / saturated bulkhead mid-run
     * (R-SEM-11). Recorded by the tripped engine group, consumed by {@link #run} to finish the
     * job INTERRUPTED (partial) rather than COMPLETED — undispatched work is surfaced for a
     * "continue as new job", never silently burned as failures.
     */
    private final Set<UUID> circuitPaused = ConcurrentHashMap.newKeySet();

    public BulkJobService(
            BulkJobRepository jobs,
            BulkJobItemRepository items,
            CorrectiveActionService actions,
            ProtectedInstanceRepository protectedInstances,
            AuditService audit,
            EngineRegistry registry,
            FlowableEngineClient client,
            Clock clock,
            InspectorProperties props,
            ApplicationEventPublisher events,
            DangerousActionReauthGate reauth) {
        this.jobs = jobs;
        this.items = items;
        this.actions = actions;
        this.protectedInstances = protectedInstances;
        this.audit = audit;
        this.registry = registry;
        this.client = client;
        this.clock = clock;
        this.props = props;
        this.events = events;
        this.reauth = reauth;
    }

    /* ------------------------------- submit ------------------------------- */

    public BulkDtos.BulkJobDto submit(BulkDtos.BulkSubmitRequest request, Authentication auth) {
        return submit(request, auth, Map.of(), BulkJob.ITEM_CAP, BulkJob.ScopeKind.SELECTION, null);
    }

    /**
     * Package-door variant for server-side-resolved submits (error-class group retry):
     * {@code extraEnvelopePayload} lands in the envelope audit row so the provenance of the
     * target list (which signature, which scan, how many resolved) is on the record.
     * {@code scopeLabel} (usability fix E1) is the caller's own short provenance summary —
     * e.g. "payment v3 · error class" — recorded on the persisted job.
     */
    BulkDtos.BulkJobDto submit(
            BulkDtos.BulkSubmitRequest request,
            Authentication auth,
            Map<String, Object> extraEnvelopePayload,
            String scopeLabel) {
        return submit(request, auth, extraEnvelopePayload, BulkJob.ITEM_CAP, BulkJob.ScopeKind.ERROR_CLASS, scopeLabel);
    }

    /**
     * Package-door for the filter-resolved path (v1.x #2), which alone may carry up to
     * {@link BulkJob#FILTER_ITEM_CAP} items; every other entry keeps the 200-item cap.
     * {@code scopeLabel} (usability fix E1) is the caller's compact criteria summary,
     * recorded on the persisted job alongside the {@code FILTER} scope kind.
     */
    BulkDtos.BulkJobDto submit(
            BulkDtos.BulkSubmitRequest request,
            Authentication auth,
            Map<String, Object> extraEnvelopePayload,
            int itemCap,
            String scopeLabel) {
        return submit(request, auth, extraEnvelopePayload, itemCap, BulkJob.ScopeKind.FILTER, scopeLabel);
    }

    private BulkDtos.BulkJobDto submit(
            BulkDtos.BulkSubmitRequest request,
            Authentication auth,
            Map<String, Object> extraEnvelopePayload,
            int itemCap,
            BulkJob.ScopeKind scopeKind,
            String scopeLabel) {
        // Dangerous-set freshness (IDP-SECURITY.md §5, R-SAFE-07): bulk is in the dangerous set
        // regardless of verb tier (it is the guard-tier-4 fan-out), so a stale OAuth2 session
        // re-authenticates ONCE — here at SUBMIT, where the session is live — never per persisted
        // item (a bulk job survives session expiry, R-SEM-10; the workers run this envelope's
        // targets under the already-freshness-checked submit). All three entry doors (selection /
        // error-class / filter) converge on this overload, so the gate cannot be bypassed.
        reauth.enforce(auth);
        ActionVerb verb = ActionVerb.fromPath(request.verb() != null ? request.verb() : "")
                .filter(BULK_VERBS::contains)
                .orElseThrow(() -> new GuardRefusedException(
                        HttpStatus.BAD_REQUEST,
                        "bulk-verb-not-allowed",
                        "Bulk supports retry-job, suspend, activate and trigger-timer in v1 — destructive bulk"
                                + " needs the tier-4 wizard. Nothing happened."));
        List<BulkDtos.BulkTarget> targets = request.items() != null ? request.items() : List.of();
        if (targets.isEmpty()) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST, "bulk-empty", "The selection is empty — nothing to do.");
        }
        if (targets.size() > itemCap) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "bulk-cap-exceeded",
                    "Bulk is capped at " + itemCap + " items (got " + targets.size()
                            + "). Narrow the selection. Nothing happened.");
        }
        // Reason is mandatory on EVERY submit door (usability fix C-back) — unified with the
        // error-class and filter siblings, which never allowed the optional escape.
        if (request.reason() == null
                || request.reason().isBlank()
                || request.reason().trim().length() < 10) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST, "reason-too-short", "The reason must be at least 10 characters.");
        }
        String reason = request.reason().trim();
        for (BulkDtos.BulkTarget target : targets) {
            registry.require(target.engineId()); // unknown engine refuses the WHOLE submit pre-flight
            if (target.instanceId() == null || target.instanceId().isBlank()) {
                throw new GuardRefusedException(
                        HttpStatus.BAD_REQUEST, "bulk-target-invalid", "Every item needs an instanceId.");
            }
        }

        // Protected auto-exclusion (R-SAFE-05): settled as skipped_protected at submit —
        // visible in the report, never silently dropped.
        Set<ProtectedInstance.Key> guarded = protectedKeys(targets);

        UUID jobId = UUID.randomUUID();
        // The envelope audit row (SPEC §7: one per item + ONE for the envelope) — the
        // fail-closed gate for the whole submission (audit down ⇒ 503, nothing recorded
        // ⇒ nothing dispatched).
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "bulk/" + verb.path() + "/v1");
        payload.put("bulkJobId", jobId.toString());
        payload.put("items", targets.size());
        payload.put(
                "targets",
                targets.stream().map(t -> t.engineId() + ":" + t.instanceId()).toList());
        if (request.continuedFrom() != null) {
            payload.put("continuedFrom", request.continuedFrom().toString());
        }
        payload.putAll(extraEnvelopePayload);
        String engineIds =
                targets.stream().map(BulkDtos.BulkTarget::engineId).distinct().collect(Collectors.joining(","));
        AuditEntry envelope = audit.beginPending(
                auth.getName(), engineIds, null, null, "bulk:" + verb.path(), reason, request.ticketId(), payload);

        // Scope provenance (usability fix E1): SELECTION derives its own label from the
        // ticked count; ERROR_CLASS/FILTER bring their own from the door that resolved them.
        String label = scopeLabel != null
                ? scopeLabel
                : targets.size() + " ticked instance" + (targets.size() == 1 ? "" : "s");
        BulkJob job = new BulkJob(
                jobId,
                auth.getName(),
                clock.instant(),
                verb.path(),
                reason,
                request.ticketId(),
                targets.size(),
                request.continuedFrom(),
                scopeKind,
                label);
        jobs.saveAndFlush(job);
        List<BulkJobItem> itemRows = new ArrayList<>();
        int ordinal = 0;
        for (BulkDtos.BulkTarget target : targets) {
            BulkJobItem item = new BulkJobItem(
                    jobId,
                    ordinal++,
                    target.engineId(),
                    target.instanceId(),
                    target.jobId(),
                    BulkJobItem.State.pending);
            if (guarded.contains(new ProtectedInstance.Key(target.engineId(), target.instanceId()))) {
                item.settle(
                        BulkJobItem.State.skipped_protected,
                        "protected instance (R-SAFE-05) — auto-excluded from bulk",
                        null,
                        clock.instant());
            }
            itemRows.add(item);
        }
        items.saveAllAndFlush(itemRows);

        executor.submit(() -> run(jobId, envelope, auth));
        events.publishEvent(new BulkJobChangedEvent(jobId));
        return BulkDtos.BulkJobDto.of(job, itemRows, true);
    }

    private Set<ProtectedInstance.Key> protectedKeys(List<BulkDtos.BulkTarget> targets) {
        List<ProtectedInstance.Key> keys = targets.stream()
                .map(t -> new ProtectedInstance.Key(t.engineId(), t.instanceId()))
                .toList();
        return protectedInstances.findAllById(keys).stream()
                .map(p -> new ProtectedInstance.Key(p.getEngineId(), p.getInstanceId()))
                .collect(Collectors.toSet());
    }

    /* ------------------------------- execution ------------------------------- */

    private void run(UUID jobId, AuditEntry envelope, Authentication auth) {
        BulkJob job = jobs.findById(jobId).orElseThrow();
        job.markRunning();
        jobs.saveAndFlush(job);
        events.publishEvent(new BulkJobChangedEvent(jobId));
        ActionVerb verb = ActionVerb.fromPath(job.getVerb()).orElseThrow();

        List<BulkJobItem> all = items.findByJobIdOrderByOrdinal(jobId);
        // Per-engine fan-out: engines proceed independently (one slow engine must not
        // starve the others), each group paced and permit-bounded (class javadoc).
        Map<String, List<BulkJobItem>> byEngine = new LinkedHashMap<>();
        for (BulkJobItem item : all) {
            if (item.getState() != BulkJobItem.State.pending) {
                continue; // settled at submit (skipped_protected)
            }
            byEngine.computeIfAbsent(item.getEngineId(), k -> new ArrayList<>()).add(item);
        }
        List<CompletableFuture<Void>> runners = new ArrayList<>();
        for (Map.Entry<String, List<BulkJobItem>> group : byEngine.entrySet()) {
            runners.add(CompletableFuture.runAsync(
                            () -> dispatchEngineGroup(job, verb, group.getKey(), group.getValue(), auth), executor)
                    .exceptionally(ex -> {
                        // An engine-group failure must never leave the job RUNNING forever.
                        log.error("bulk job {} engine group {} failed", jobId, group.getKey(), ex);
                        return null;
                    }));
        }
        CompletableFuture.allOf(runners.toArray(CompletableFuture[]::new)).join();

        boolean cancelled = cancelRequested.getOrDefault(jobId, false);
        // R-SEM-11: a circuit-open pause leaves undispatched items `pending`. They are NOT
        // failures — settle them not_run (never attempted) and finish INTERRUPTED so the
        // "continue as new job" affordance + "N of M dispatched" honesty apply. Cancel wins.
        boolean paused = circuitPaused.remove(jobId) && !cancelled;
        if (cancelled || paused) {
            String reason = cancelled
                    ? "job cancelled before dispatch"
                    : "engine circuit open mid-job — dispatch paused; not attempted (continue as a new job)";
            for (BulkJobItem item : all) {
                if (item.getState() == BulkJobItem.State.pending) {
                    item.settle(BulkJobItem.State.not_run, reason, null, clock.instant());
                }
            }
            items.saveAllAndFlush(all);
        }
        cancelRequested.remove(jobId);
        BulkJob.State terminal =
                cancelled ? BulkJob.State.CANCELLED : paused ? BulkJob.State.INTERRUPTED : BulkJob.State.COMPLETED;
        job.finish(terminal, clock.instant());
        jobs.saveAndFlush(job);
        closeEnvelope(envelope, all);
        events.publishEvent(new BulkJobChangedEvent(jobId));
    }

    /**
     * One engine's dispatch loop: a permit per in-flight item (pool shared with every other
     * running job targeting this engine) and a mandatory pause between dispatch STARTS —
     * the do-no-harm stagger (SPEC §7). The pacer thread sleeps; workers run per item.
     */
    private void dispatchEngineGroup(
            BulkJob job, ActionVerb verb, String engineId, List<BulkJobItem> group, Authentication auth) {
        Semaphore permits = enginePermits.computeIfAbsent(
                engineId, id -> new Semaphore(props.bulkOrDefault().enginePermitsOrDefault()));
        long staggerMs = props.bulkOrDefault().staggerMsOrDefault();
        List<CompletableFuture<Void>> inFlight = new ArrayList<>();
        // Circuit-open PAUSE (R-SEM-11): once a worker fast-fails on a tripped breaker, stop
        // starting new items on this engine — the rest stay `pending`, settled to not_run in run().
        AtomicBoolean paused = new AtomicBoolean(false);
        boolean first = true;
        for (BulkJobItem item : group) {
            if (cancelRequested.getOrDefault(job.getId(), false) || paused.get()) {
                break; // stop DISPATCHING — in-flight items keep their outcome (SPEC §7)
            }
            if (!first) {
                pace(staggerMs);
            }
            first = false;
            permits.acquireUninterruptibly();
            // Re-check after acquiring the permit: a worker may have tripped the pause while we
            // waited for the permit, so this closes the window on burning an undispatched item.
            if (cancelRequested.getOrDefault(job.getId(), false) || paused.get()) {
                permits.release();
                break;
            }
            item.markDispatched();
            items.saveAndFlush(item);
            inFlight.add(CompletableFuture.runAsync(
                            () -> {
                                try {
                                    if (dispatchOne(job, verb, item, auth)) {
                                        paused.set(true);
                                    }
                                    items.saveAndFlush(item);
                                    events.publishEvent(new BulkJobChangedEvent(job.getId()));
                                } finally {
                                    permits.release();
                                }
                            },
                            executor)
                    .exceptionally(ex -> {
                        log.error(
                                "bulk item {}:{} dispatch thread failed", item.getEngineId(), item.getInstanceId(), ex);
                        return null;
                    }));
        }
        CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).join();
        if (paused.get()) {
            circuitPaused.add(job.getId());
            log.warn(
                    "bulk job {} paused dispatch to engine {} — circuit open / bulkhead full; undispatched items held",
                    job.getId(),
                    engineId);
        }
    }

    /** Virtual-thread pause — non-blocking for the carrier; interruption just stops pacing. */
    private static void pace(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One item through the full single-target rails; outcome classes per SPEC §7. */
    /**
     * Dispatch one item and settle it. Returns {@code true} iff the engine fast-failed on an
     * open circuit / saturated bulkhead ({@code engine-shedding-load}) — an ENGINE-WIDE
     * condition, not a per-item verdict: the caller must PAUSE dispatch to this engine so the
     * remaining items are not burned as failures (R-SEM-11). The fast-failed item itself is a
     * clean {@code failed} (nothing left the BFF); every other outcome returns {@code false}.
     */
    private boolean dispatchOne(BulkJob job, ActionVerb verb, BulkJobItem item, Authentication auth) {
        try {
            if (verb == ActionVerb.RETRY_JOB) {
                dispatchRetry(job, item, auth);
                return false;
            }
            ActionRequest request = requestFor(job, verb, item.getJobRef());
            ActionResult result = actions.execute(item.getEngineId(), item.getInstanceId(), verb, request, auth);
            item.settle(BulkJobItem.State.ok, result.deltaStatement(), result.auditId(), clock.instant());
            return false;
        } catch (GuardRefusedException e) {
            item.settle(guardOutcome(e), e.code() + ": " + e.getMessage(), null, clock.instant());
            return "engine-shedding-load".equals(e.code());
        } catch (CasConflictException e) {
            // Concurrent-operator rule (R-SEM-09): the target moved — already handled.
            item.settle(BulkJobItem.State.skipped, "already changed by someone else", e.auditId(), clock.instant());
            return false;
        } catch (EngineRejectedException e) {
            item.settle(
                    BulkJobItem.State.failed,
                    "engine rejected (" + e.engineStatus() + "): " + e.engineBody(),
                    e.auditId(),
                    clock.instant());
            return false;
        } catch (OutcomeUnknownException e) {
            item.settle(
                    BulkJobItem.State.unknown,
                    "no answer within the write budget — may have applied; use Verify now",
                    e.auditId(),
                    clock.instant());
            return false;
        } catch (OutcomeVerificationFailedException e) {
            item.settle(
                    BulkJobItem.State.unknown,
                    "dispatched — outcome verification failed; use Verify now",
                    e.auditId(),
                    clock.instant());
            return false;
        } catch (RuntimeException e) {
            log.error("bulk item {}:{} unexpected failure", item.getEngineId(), item.getInstanceId(), e);
            item.settle(BulkJobItem.State.failed, "unexpected: " + e.getMessage(), null, clock.instant());
            return false;
        }
    }

    /**
     * retry-job resolves the instance's dead-letter jobs at DISPATCH time — the SPEC §7
     * per-item precondition recheck ("still in the DLQ?"). None left ⇒ skipped, honestly.
     */
    private void dispatchRetry(BulkJob job, BulkJobItem item, Authentication auth) {
        EngineConfig engine = registry.require(item.getEngineId());
        List<String> jobIds;
        if (item.getJobRef() != null && !item.getJobRef().isBlank()) {
            jobIds = List.of(item.getJobRef());
        } else {
            jobIds = client
                    .listJobs(
                            engine,
                            JobLaneKind.DEADLETTER,
                            Map.of("processInstanceId", item.getInstanceId()),
                            0,
                            DLQ_JOBS_PER_INSTANCE_CAP)
                    .dataOrEmpty()
                    .stream()
                    .map(row -> String.valueOf(row.get("id")))
                    .toList();
        }
        if (jobIds.isEmpty()) {
            item.settle(
                    BulkJobItem.State.skipped,
                    "no dead-letter jobs on this instance any more (already retried?)",
                    null,
                    clock.instant());
            return;
        }
        int retried = 0;
        UUID lastAudit = null;
        for (String dlqJobId : jobIds) {
            ActionResult result = actions.execute(
                    item.getEngineId(),
                    item.getInstanceId(),
                    ActionVerb.RETRY_JOB,
                    requestFor(job, ActionVerb.RETRY_JOB, dlqJobId),
                    auth);
            lastAudit = result.auditId();
            retried++;
        }
        item.settle(
                BulkJobItem.State.ok,
                retried == 1
                        ? "job " + jobIds.get(0) + " moved back to the executable queue"
                        : retried + " dead-letter jobs moved back to the executable queue",
                lastAudit,
                clock.instant());
    }

    private ActionRequest requestFor(BulkJob job, ActionVerb verb, String jobRef) {
        return new ActionRequest(
                job.getReason(),
                job.getTicketId(),
                null,
                needsJobRef(verb) ? jobRef : null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static boolean needsJobRef(ActionVerb verb) {
        return verb == ActionVerb.RETRY_JOB || verb == ActionVerb.TRIGGER_TIMER;
    }

    private static BulkJobItem.State guardOutcome(GuardRefusedException e) {
        // "already resolved" class (R-SEM-09): the target is gone/done — a NORMAL outcome.
        if ("job-gone".equals(e.code()) || "instance-not-running".equals(e.code())) {
            return BulkJobItem.State.skipped;
        }
        if ("instance-protected".equals(e.code())) {
            return BulkJobItem.State.skipped_protected;
        }
        // Everything else (rbac, read-only engine, unreachable, shedding load) is a clean
        // pre-flight rejection: nothing happened ⇒ failed, retriable in a follow-up job.
        return BulkJobItem.State.failed;
    }

    private void closeEnvelope(AuditEntry envelope, List<BulkJobItem> all) {
        Map<BulkJobItem.State, Long> tally =
                all.stream().collect(Collectors.groupingBy(BulkJobItem::getState, Collectors.counting()));
        boolean anyUnknown = tally.getOrDefault(BulkJobItem.State.unknown, 0L) > 0;
        boolean anyFailed = tally.getOrDefault(BulkJobItem.State.failed, 0L) > 0;
        AuditOutcome outcome = anyUnknown ? AuditOutcome.unknown : anyFailed ? AuditOutcome.failed : AuditOutcome.ok;
        try {
            audit.close(envelope, outcome, null, "per-item tally: " + tally, false);
        } catch (RuntimeException e) {
            log.error("bulk envelope audit close failed for {}: {}", envelope.getId(), e.toString());
        }
    }

    /* ------------------------------- reads ------------------------------- */

    public List<BulkDtos.BulkJobDto> recent(int limit) {
        return jobs.findAllByOrderBySubmittedAtDesc(PageRequest.of(0, Math.min(Math.max(limit, 1), 100))).stream()
                .map(job -> BulkDtos.BulkJobDto.of(job, items.findByJobIdOrderByOrdinal(job.getId()), false))
                .toList();
    }

    public BulkDtos.BulkJobDto get(UUID id) {
        BulkJob job = jobs.findById(id)
                .orElseThrow(() ->
                        new GuardRefusedException(HttpStatus.NOT_FOUND, "bulk-job-unknown", "No bulk job " + id + "."));
        return BulkDtos.BulkJobDto.of(job, items.findByJobIdOrderByOrdinal(id), true);
    }

    /* ------------------------------- cancel ------------------------------- */

    /** Cancel stops DISPATCHING (SPEC §7) — items already sent keep their outcome. */
    public BulkDtos.BulkJobDto cancel(UUID id, Authentication auth) {
        BulkJob job = jobs.findById(id)
                .orElseThrow(() ->
                        new GuardRefusedException(HttpStatus.NOT_FOUND, "bulk-job-unknown", "No bulk job " + id + "."));
        if (job.getState() != BulkJob.State.PENDING && job.getState() != BulkJob.State.RUNNING) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "bulk-job-finished",
                    "Job " + id + " is already " + job.getState() + " — nothing to cancel.");
        }
        log.info("bulk job {} cancel requested by {}", id, auth.getName());
        cancelRequested.put(id, true);
        events.publishEvent(new BulkJobChangedEvent(id));
        return BulkDtos.BulkJobDto.of(job, items.findByJobIdOrderByOrdinal(id), true);
    }

    /* ------------------------------- verify-now (R-SAFE-09) ------------------------------- */

    /**
     * Re-runs the verb's precondition predicate against LIVE engine state and
     * reclassifies the {@code unknown} item with evidence — never re-fires the mutation.
     */
    public BulkDtos.BulkItemDto verifyNow(UUID jobId, int ordinal) {
        BulkJobItem item = items.findById(new BulkJobItem.Key(jobId, ordinal))
                .orElseThrow(() -> new GuardRefusedException(
                        HttpStatus.NOT_FOUND, "bulk-item-unknown", "No item " + ordinal + " in job " + jobId + "."));
        if (item.getState() != BulkJobItem.State.unknown) {
            throw new GuardRefusedException(
                    HttpStatus.CONFLICT,
                    "bulk-item-not-unknown",
                    "Item " + ordinal + " is '" + item.getState() + "' — Verify now applies to unknown outcomes only.");
        }
        BulkJob job = jobs.findById(jobId).orElseThrow();
        ActionVerb verb = ActionVerb.fromPath(job.getVerb()).orElseThrow();
        EngineConfig engine = registry.require(item.getEngineId());
        Verdict verdict;
        try {
            verdict = precondition(engine, verb, item);
        } catch (RuntimeException e) {
            verdict = new Verdict(null, "engine did not answer the verification read: " + e.getMessage());
        }
        if (verdict.reclassifyTo() != null) {
            item.settle(verdict.reclassifyTo(), verdict.evidence(), item.getAuditId(), clock.instant());
        } else {
            // Still ambiguous: stays unknown, but the evidence is refreshed for the drawer.
            item.settle(BulkJobItem.State.unknown, verdict.evidence(), item.getAuditId(), item.getFinishedAt());
        }
        items.saveAndFlush(item);
        events.publishEvent(new BulkJobChangedEvent(jobId));
        return BulkDtos.BulkItemDto.of(item);
    }

    private record Verdict(BulkJobItem.State reclassifyTo, String evidence) {}

    private Verdict precondition(EngineConfig engine, ActionVerb verb, BulkJobItem item) {
        switch (verb) {
            case RETRY_JOB -> {
                if (item.getJobRef() == null) {
                    return new Verdict(null, "no job reference recorded — needs L3 review of the audit trail");
                }
                Map<String, Object> dlq = client.getJob(engine, JobLaneKind.DEADLETTER, item.getJobRef());
                return dlq == null
                        ? new Verdict(
                                BulkJobItem.State.ok,
                                "verified: job " + item.getJobRef() + " is no longer dead-lettered")
                        : new Verdict(
                                null,
                                "job " + item.getJobRef()
                                        + " is STILL dead-lettered — the retry may not have applied (or it failed"
                                        + " again); needs L3");
            }
            case TRIGGER_TIMER -> {
                if (item.getJobRef() == null) {
                    return new Verdict(null, "no timer reference recorded — needs L3 review of the audit trail");
                }
                Map<String, Object> timer = client.getJob(engine, JobLaneKind.TIMER, item.getJobRef());
                return timer == null
                        ? new Verdict(BulkJobItem.State.ok, "verified: timer " + item.getJobRef() + " has fired")
                        : new Verdict(null, "timer " + item.getJobRef() + " is still queued — still-pending");
            }
            case SUSPEND, ACTIVATE -> {
                Map<String, Object> instance = client.getRuntimeProcessInstance(engine, item.getInstanceId());
                if (instance == null) {
                    return new Verdict(null, "instance is no longer running — completed or deleted since; needs L3");
                }
                boolean suspended = Boolean.TRUE.equals(instance.get("suspended"));
                boolean wanted = verb == ActionVerb.SUSPEND;
                return suspended == wanted
                        ? new Verdict(
                                BulkJobItem.State.ok, "verified: instance is " + (suspended ? "suspended" : "active"))
                        : new Verdict(
                                null,
                                "instance is " + (suspended ? "suspended" : "active") + " — the " + verb.path()
                                        + " likely did not apply; still-pending");
            }
            default -> {
                return new Verdict(null, "no precondition predicate for '" + verb.path() + "' — needs L3");
            }
        }
    }

    /* ------------------------------- reconciliation (SPEC §7) ------------------------------- */

    /** Startup sweep: no automatic resume, EVER — interrupted work is surfaced, not re-fired. */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileInterrupted() {
        List<BulkJob> stale;
        try {
            stale = jobs.findByStateIn(List.of(BulkJob.State.PENDING, BulkJob.State.RUNNING));
        } catch (RuntimeException e) {
            log.warn("bulk reconciliation sweep skipped — store unavailable: {}", e.toString());
            return;
        }
        for (BulkJob job : stale) {
            List<BulkJobItem> all = items.findByJobIdOrderByOrdinal(job.getId());
            for (BulkJobItem item : all) {
                if (item.getState() == BulkJobItem.State.dispatched) {
                    // In flight at crash: the engine may have applied it. NEVER re-fired.
                    item.settle(
                            BulkJobItem.State.unknown,
                            "in flight when the BFF stopped — may have applied; use Verify now",
                            item.getAuditId(),
                            clock.instant());
                } else if (item.getState() == BulkJobItem.State.pending) {
                    item.settle(BulkJobItem.State.not_run, "BFF stopped before dispatch", null, clock.instant());
                }
            }
            items.saveAllAndFlush(all);
            BulkJob.State before = job.getState();
            job.finish(BulkJob.State.INTERRUPTED, clock.instant());
            jobs.saveAndFlush(job);
            log.warn(
                    "bulk job {} ({} × {}) swept {} → INTERRUPTED — offer 'continue as new job'",
                    job.getId(),
                    job.getTotalItems(),
                    job.getVerb(),
                    before);
        }
    }
}
