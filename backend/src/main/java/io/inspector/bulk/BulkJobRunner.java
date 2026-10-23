package io.inspector.bulk;

import io.inspector.action.ActionRequest;
import io.inspector.action.ActionResult;
import io.inspector.action.ActionVerb;
import io.inspector.action.CasConflictException;
import io.inspector.action.EngineRejectedException;
import io.inspector.action.GuardRefusedException;
import io.inspector.action.OutcomeUnknownException;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditOutcome;
import io.inspector.audit.OutcomeVerificationFailedException;
import io.inspector.client.GuardedCaller;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient.JobLaneKind;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.micrometer.core.instrument.Counter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;

/** Dispatch loop. Moved from {@link BulkJobService} (issue #475). */
final class BulkJobRunner {

    private static final int DLQ_JOBS_PER_INSTANCE_CAP = 20;

    private final BulkJobService host;

    BulkJobRunner(BulkJobService host) {
        this.host = host;
    }

    /* ------------------------------- execution ------------------------------- */

    /**
     * The dispatch-loop try/catch/finally (#303 defect 2): {@link #runInternal} used to leave
     * {@code findById/markRunning}, the item load, the paused/cancelled settle, and
     * {@code finish}/{@code closeEnvelope} completely unguarded — a store blip at any one of
     * those left the job {@code RUNNING} forever, recoverable only at the next BFF restart. Any
     * {@link RuntimeException} escaping {@link #runInternal} is now caught here and the job is
     * settled {@code INTERRUPTED} from whatever the store can still show
     * ({@link #settleAfterRunFailure}); the {@code finally} always releases this job's entries in
     * {@link #cancelRequested}/{@link #circuitPaused} (fresh UUIDs are never reused across
     * submits, so a leaked entry is an unbounded memory leak, not a functional bug, but still
     * worth closing) whether {@link #runInternal} succeeded, threw, or was recovered.
     */
    void run(UUID jobId, AuditEntry envelope, Authentication auth) {
        try {
            runInternal(jobId, envelope, auth);
        } catch (RuntimeException e) {
            host.log.error("bulk job {} run() failed — settling INTERRUPTED from the last observable state", jobId, e);
            host.settleAfterRunFailure(jobId);
        } finally {
            host.cancelRequested.remove(jobId);
            host.circuitPaused.remove(jobId);
        }
    }

    private void runInternal(UUID jobId, AuditEntry envelope, Authentication auth) {
        BulkJob job = host.jobs.findById(jobId).orElseThrow();
        job.markRunning();
        host.jobs.saveAndFlush(job);
        host.events.publishEvent(new BulkJobChangedEvent(jobId));
        ActionVerb verb = ActionVerb.fromPath(job.getVerb()).orElseThrow();

        List<BulkJobItem> all = host.items.findByJobIdOrderByOrdinal(jobId);
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
                            () -> dispatchEngineGroup(job, verb, group.getKey(), group.getValue(), auth), host.executor)
                    .exceptionally(ex -> {
                        // An engine-group failure must never leave the job RUNNING forever.
                        host.log.error("bulk job {} engine group {} failed", jobId, group.getKey(), ex);
                        return null;
                    }));
        }
        CompletableFuture.allOf(runners.toArray(CompletableFuture[]::new)).join();

        boolean cancelled = host.cancelRequested.getOrDefault(jobId, false);
        // R-SEM-11: a circuit-open pause leaves undispatched items `pending`. They are NOT
        // failures — settle them not_run (never attempted) and finish INTERRUPTED so the
        // "continue as new job" affordance + "N of M dispatched" honesty apply. Cancel wins.
        boolean paused = host.circuitPaused.remove(jobId) && !cancelled;
        if (cancelled || paused) {
            String reason = cancelled
                    ? "job cancelled before dispatch"
                    : "engine circuit open mid-job — dispatch paused; not attempted (continue as a new job)";
            for (BulkJobItem item : all) {
                if (item.getState() == BulkJobItem.State.pending) {
                    item.settle(BulkJobItem.State.not_run, reason, null, host.clock.instant());
                }
            }
            host.items.saveAllAndFlush(all);
        }
        // cancelRequested/circuitPaused cleanup for this jobId now lives in run()'s finally —
        // circuitPaused was already consumed above (its return value drives `paused`).
        BulkJob.State terminal =
                cancelled ? BulkJob.State.CANCELLED : paused ? BulkJob.State.INTERRUPTED : BulkJob.State.COMPLETED;
        job.finish(terminal, host.clock.instant());
        host.jobs.saveAndFlush(job);
        closeEnvelope(envelope, all);
        host.events.publishEvent(new BulkJobChangedEvent(jobId));
    }

    /**
     * One engine's dispatch loop: a permit per in-flight item (pool shared with every other
     * running job targeting this engine) and a mandatory pause between dispatch STARTS —
     * the do-no-harm stagger (SPEC §7). The pacer thread sleeps; workers run per item.
     */
    private void dispatchEngineGroup(
            BulkJob job, ActionVerb verb, String engineId, List<BulkJobItem> group, Authentication auth) {
        Semaphore permits = host.enginePermits.computeIfAbsent(
                engineId, id -> new Semaphore(host.props.bulkOrDefault().enginePermitsOrDefault()));
        long staggerMs = host.props.bulkOrDefault().staggerMsOrDefault();
        List<CompletableFuture<Void>> inFlight = new ArrayList<>();
        // Circuit-open PAUSE (R-SEM-11): once a worker fast-fails on a tripped breaker, stop
        // starting new items on this engine — the rest stay `pending`, settled to not_run in run().
        AtomicBoolean paused = new AtomicBoolean(false);
        boolean first = true;
        for (BulkJobItem item : group) {
            if (host.cancelRequested.getOrDefault(job.getId(), false) || paused.get()) {
                break; // stop DISPATCHING — in-flight items keep their outcome (SPEC §7)
            }
            if (!first) {
                pace(staggerMs);
            }
            first = false;
            permits.acquireUninterruptibly();
            // Re-check after acquiring the permit: a worker may have tripped the pause while we
            // waited for the permit, so this closes the window on burning an undispatched item.
            if (host.cancelRequested.getOrDefault(job.getId(), false) || paused.get()) {
                permits.release();
                break;
            }
            item.markDispatched();
            host.items.saveAndFlush(item);
            inFlight.add(CompletableFuture.runAsync(
                            () -> {
                                try {
                                    if (dispatchOne(job, verb, item, auth)) {
                                        paused.set(true);
                                    }
                                    host.items.saveAndFlush(item);
                                    host.events.publishEvent(new BulkJobChangedEvent(job.getId()));
                                } finally {
                                    permits.release();
                                }
                            },
                            host.executor)
                    .exceptionally(ex -> {
                        host.log.error(
                                "bulk item {}:{} dispatch thread failed", item.getEngineId(), item.getInstanceId(), ex);
                        return null;
                    }));
        }
        CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).join();
        if (paused.get()) {
            host.circuitPaused.add(job.getId());
            host.log.warn(
                    "bulk job {} paused dispatch to engine {} — circuit open / bulkhead full; undispatched items held",
                    job.getId(),
                    engineId);
        }
    }

    /**
     * Bounded wait for one engine's breaker to leave OPEN (R-SEM-11, issue #101) — polls {@link
     * GuardedCaller#isOpen} rather than dispatching a doomed call. Returns {@code true} the moment
     * it's no longer OPEN (worth a retry — {@code HALF_OPEN}'s trial calls included); {@code
     * false} once {@code circuitPauseMaxMs} elapses or cancel is requested, in which case the
     * caller falls through to the existing honest give-up path (settle failed, stop this engine's
     * dispatch, offer "continue as new job"). Runs on the item's own virtual thread — cheap,
     * never blocks the shared engine permit the item already holds from being released promptly.
     *
     * <p>Since multiple items typically enter this wait around the same moment (they all hit the
     * same open breaker — see {@link #enginePermits}'s javadoc), a HALF_OPEN transition can
     * thundering-herd them: resilience4j permits only a FEW trial calls in HALF_OPEN, so several
     * items may read {@code isOpen()==false} and retry simultaneously, but only the permitted
     * trials actually get through — the rest immediately re-trip and settle {@code failed} via
     * the single bounded retry ({@code isBoundedRetry} prevents a second wait, never a loop).
     * Accepted as a pessimistic-but-safe outcome, not fixed with jitter/backoff — those items are
     * exactly as truthfully {@code failed} as they'd have been without this whole feature.
     *
     * <p>Timed against {@link System#nanoTime()}, deliberately NOT the injected {@link #clock} —
     * that clock is fixed in every test (for deterministic item timestamps), so a fixed-clock
     * deadline would never elapse and this bound would hang forever under test.
     */
    private boolean awaitCircuitRecovery(BulkJob job, String engineId) {
        long maxWaitMs = host.props.bulkOrDefault().circuitPauseMaxMsOrDefault();
        long pollMs = host.props.bulkOrDefault().circuitPausePollMsOrDefault();
        host.log.warn(
                "bulk job {} pausing item dispatch on engine {} — circuit open, waiting up to {}ms",
                job.getId(),
                engineId,
                maxWaitMs);
        long deadlineNanos = System.nanoTime() + maxWaitMs * 1_000_000L;
        while (System.nanoTime() < deadlineNanos) {
            if (host.cancelRequested.getOrDefault(job.getId(), false)) {
                return false;
            }
            pace(pollMs);
            if (!host.guardedCaller.isOpen(engineId, CallPriority.INTERACTIVE)) {
                host.log.info("bulk job {} engine {} circuit recovered — resuming dispatch", job.getId(), engineId);
                return true;
            }
        }
        host.log.warn(
                "bulk job {} engine {} circuit still open after {}ms — giving up on this item, pausing the rest",
                job.getId(),
                engineId,
                maxWaitMs);
        return false;
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
     * Dispatch one item and settle it. Returns {@code true} iff the engine is STILL shedding
     * load ({@code engine-shedding-load}) after the bounded wait-and-retry below has already been
     * exhausted — an ENGINE-WIDE condition, not a per-item verdict: the caller must PAUSE
     * dispatch to this engine so the remaining items are not burned as failures (R-SEM-11). Every
     * other outcome, including a RECOVERED retry, returns {@code false}.
     */
    private boolean dispatchOne(BulkJob job, ActionVerb verb, BulkJobItem item, Authentication auth) {
        return dispatchOne(job, verb, item, auth, false);
    }

    private boolean dispatchOne(
            BulkJob job, ActionVerb verb, BulkJobItem item, Authentication auth, boolean isBoundedRetry) {
        try {
            if (verb == ActionVerb.RETRY_JOB) {
                dispatchRetry(job, item, auth);
                return false;
            }
            ActionRequest request = requestFor(job, verb, item.getJobRef());
            ActionResult result =
                    host.actions.executeBulkItem(item.getEngineId(), item.getInstanceId(), verb, request, auth);
            item.settle(BulkJobItem.State.ok, result.deltaStatement(), result.auditId(), host.clock.instant());
            return false;
        } catch (GuardRefusedException e) {
            // Bounded pause-and-retry (issue #101, R-SEM-11): "engine-shedding-load" wraps EITHER
            // CallNotPermittedException (breaker OPEN) or BulkheadFullException (bulkhead full) —
            // see GuardedCaller.call()'s bulkhead-outside-breaker composition and
            // CorrectiveActionService's shared catch. Resilience4j's contract for BOTH gates the
            // SAME way: acquirePermission()/tryAcquirePermission() is checked, and the exception
            // thrown, strictly BEFORE the wrapped operation ever runs — so a retry after either
            // one clears is safe, never a double-send, for the same reason in both cases. Exactly
            // one retry attempt (isBoundedRetry guards re-entry) so a flickering breaker falls
            // through to the honest give-up path instead of looping.
            if (!isBoundedRetry
                    && "engine-shedding-load".equals(e.code())
                    && awaitCircuitRecovery(job, item.getEngineId())) {
                return dispatchOne(job, verb, item, auth, true);
            }
            item.settle(guardOutcome(e), e.code() + ": " + e.getMessage(), null, host.clock.instant());
            return "engine-shedding-load".equals(e.code());
        } catch (CasConflictException e) {
            // Concurrent-operator rule (R-SEM-09): the target moved — already handled.
            item.settle(
                    BulkJobItem.State.skipped, "already changed by someone else", e.auditId(), host.clock.instant());
            return false;
        } catch (EngineRejectedException e) {
            item.settle(
                    BulkJobItem.State.failed,
                    "engine rejected (" + e.engineStatus() + "): " + e.engineBody(),
                    e.auditId(),
                    host.clock.instant());
            return false;
        } catch (OutcomeUnknownException e) {
            item.settle(
                    BulkJobItem.State.unknown,
                    "no answer within the write budget — may have applied; use Verify now",
                    e.auditId(),
                    host.clock.instant());
            return false;
        } catch (OutcomeVerificationFailedException e) {
            item.settle(
                    BulkJobItem.State.unknown,
                    "dispatched — outcome verification failed; use Verify now",
                    e.auditId(),
                    host.clock.instant());
            return false;
        } catch (RuntimeException e) {
            host.log.error("bulk item {}:{} unexpected failure", item.getEngineId(), item.getInstanceId(), e);
            item.settle(BulkJobItem.State.failed, "unexpected: " + e.getMessage(), null, host.clock.instant());
            return false;
        }
    }

    /**
     * retry-job resolves the instance's dead-letter jobs at DISPATCH time — the SPEC §7
     * per-item precondition recheck ("still in the DLQ?"). None left ⇒ skipped, honestly.
     */
    private void dispatchRetry(BulkJob job, BulkJobItem item, Authentication auth) {
        EngineConfig engine = host.registry.require(item.getEngineId());
        List<String> jobIds;
        if (item.getJobRef() != null && !item.getJobRef().isBlank()) {
            jobIds = List.of(item.getJobRef());
        } else {
            jobIds = host
                    .client
                    .listJobs(
                            engine,
                            CallPriority.INTERACTIVE,
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
                    host.clock.instant());
            return;
        }
        int retried = 0;
        UUID lastAudit = null;
        for (String dlqJobId : jobIds) {
            ActionResult result = host.actions.executeBulkItem(
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
                host.clock.instant());
    }

    private ActionRequest requestFor(BulkJob job, ActionVerb verb, String jobRef) {
        boolean task = verb == ActionVerb.REASSIGN_TASK || verb == ActionVerb.UNASSIGN_TASK;
        return new ActionRequest(
                job.getReason(),
                job.getTicketId(),
                null,
                needsJobRef(verb) ? jobRef : null,
                task ? jobRef : null,
                null,
                null,
                null,
                null,
                null,
                task && verb == ActionVerb.REASSIGN_TASK ? job.getAssignee() : null);
    }

    private static boolean needsJobRef(ActionVerb verb) {
        return verb == ActionVerb.RETRY_JOB || verb == ActionVerb.TRIGGER_TIMER;
    }

    private static BulkJobItem.State guardOutcome(GuardRefusedException e) {
        // "already resolved" class (R-SEM-09): the target is gone/done — a NORMAL outcome.
        if ("job-gone".equals(e.code())
                || "instance-not-running".equals(e.code())
                || "task-gone".equals(e.code())
                || "task-not-active".equals(e.code())
                || "history-gone".equals(e.code())) {
            return BulkJobItem.State.skipped;
        }
        if ("instance-protected".equals(e.code())) {
            return BulkJobItem.State.skipped_protected;
        }
        // Everything else (rbac, read-only engine, unreachable, shedding load) is a clean
        // pre-flight rejection: nothing happened ⇒ failed, retriable in a follow-up job.
        return BulkJobItem.State.failed;
    }

    void closeEnvelope(AuditEntry envelope, List<BulkJobItem> all) {
        Map<BulkJobItem.State, Long> tally =
                all.stream().collect(Collectors.groupingBy(BulkJobItem::getState, Collectors.counting()));
        // OPERATIONS.md §2 (issue #96): per-item outcome counters, tallied once per finished job
        // rather than at each individual settle() call site — semantically identical (every item
        // is counted exactly once) with one hook point instead of the half-dozen dispatch/verify/
        // reconcile sites that call settle().
        tally.forEach((state, count) -> Counter.builder("bulk_item_outcomes_total")
                .description("Bulk job item outcomes, tallied once per finished job")
                .tag("state", state.name())
                .register(host.metrics)
                .increment(count));
        boolean anyUnknown = tally.getOrDefault(BulkJobItem.State.unknown, 0L) > 0;
        boolean anyFailed = tally.getOrDefault(BulkJobItem.State.failed, 0L) > 0;
        AuditOutcome outcome = anyUnknown ? AuditOutcome.unknown : anyFailed ? AuditOutcome.failed : AuditOutcome.ok;
        try {
            host.audit.close(envelope, outcome, null, "per-item tally: " + tally, false);
        } catch (RuntimeException e) {
            host.log.error("bulk envelope audit close failed for {}: {}", envelope.getId(), e.toString());
        }
    }
}
