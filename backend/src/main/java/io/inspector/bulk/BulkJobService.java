package io.inspector.bulk;

import io.inspector.action.ActionVerb;
import io.inspector.action.CorrectiveActionService;
import io.inspector.action.GuardRefusedException;
import io.inspector.api.MdcPropagatingExecutors;
import io.inspector.audit.AuditService;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.GuardedCaller;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessApiClient.JobLaneKind;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.reauth.DangerousActionReauthGate;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
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

    static final Logger log = LoggerFactory.getLogger(BulkJobService.class);

    /** SPEC §7 v1 verb whitelist: queue-state verbs, dispatched via the SELECTION/ERROR_CLASS/FILTER doors. */
    private static final Set<ActionVerb> BULK_VERBS = Set.of(
            ActionVerb.RETRY_JOB,
            ActionVerb.SUSPEND,
            ActionVerb.ACTIVATE,
            ActionVerb.TRIGGER_TIMER,
            ActionVerb.REASSIGN_TASK,
            ActionVerb.UNASSIGN_TASK);

    /**
     * Tier-4 destructive-bulk wizard (SPEC §6/§7, issue #100): a SEPARATE, stricter whitelist —
     * only {@link DestructiveBulkService} may submit through it (its own door, its own
     * refuse-unscoped + typed-count-drift + ADMIN-hard-gate rails). {@code delete-deadletter}
     * bulk is intentionally NOT included yet — it needs a job-level (not instance-level) scope
     * resolver; deferred as a documented fast-follow (IMPLEMENTATION-PLAN.md), not silently
     * dropped.
     */
    private static final Set<ActionVerb> DESTRUCTIVE_BULK_VERBS = Set.of(ActionVerb.TERMINATE_DELETE);

    private static final Set<ActionVerb> HISTORY_VERBS =
            Set.of(ActionVerb.DELETE_HISTORY, ActionVerb.DELETE_CASE_HISTORY);

    final BulkJobRepository jobs;
    final BulkJobItemRepository items;
    final CorrectiveActionService actions;
    final ProtectedInstanceRepository protectedInstances;
    final io.inspector.audit.ProtectedDefinitionRepository protectedDefinitions;
    final AuditService audit;
    final EngineRegistry registry;
    final ProcessApiClient client;
    final Clock clock;
    final InspectorProperties props;
    final ApplicationEventPublisher events;
    private final DangerousActionReauthGate reauth;
    private final BulkJobRunner runner;
    private final BulkJobSubmitter submitter;
    final GuardedCaller guardedCaller;
    final MeterRegistry metrics;
    final ExecutorService executor = MdcPropagatingExecutors.newVirtualThreadPerTaskExecutor();
    final Map<UUID, Boolean> cancelRequested = new ConcurrentHashMap<>();
    /**
     * Per-engine in-flight dispatch permits, shared across concurrent jobs (SPEC §7).
     *
     * <p><b>Accepted tradeoff (issue #101 review, R-SEM-11):</b> a permit is held for the ENTIRE
     * duration of {@link #awaitCircuitRecovery}, not just the dispatch attempt — an item that
     * fast-fails on a genuinely open circuit camps on its permit for up to {@code
     * circuitPauseMaxMs} (default 20s) while polling for recovery. During a real circuit-open
     * incident, up to {@code enginePermits} (default 4) concurrently in-flight items pile into
     * this wait roughly simultaneously (they all hit the same open breaker), soft-locking THAT
     * ENGINE's shared permit pool for up to the bound — any OTHER concurrent job's dispatch to
     * the SAME engine blocks on {@link Semaphore#acquireUninterruptibly()} until a permit frees.
     * Deliberately not fixed by releasing-and-reacquiring the permit around the wait: doing so
     * would let the per-item {@code paused} gate in {@link #dispatchEngineGroup} start MORE new
     * dispatches into the same known-open breaker while this item waits, which is the exact
     * pile-up this pause exists to prevent — trading a bounded, per-engine throughput dip (never
     * fleet-wide; each engine has its own {@link Semaphore}) for genuinely uncontrolled load on
     * a struggling engine. Considered and rejected a "pause immediately, not after the wait"
     * variant too: the per-engine dispatch loop is a synchronous {@code for} that {@code break}s
     * on pause and cannot resume mid-loop, so signaling pause before the wait resolves would
     * silently abandon the REST of the group's items even when this one item's wait succeeds —
     * worse than the current behavior, not better. A genuinely non-starving fix needs pre-flight
     * {@code isOpen} checks ahead of every dispatch attempt (not just the reactive retry here),
     * which is a bigger redesign than this P3 follow-up warrants.
     */
    final Map<String, Semaphore> enginePermits = new ConcurrentHashMap<>();
    /**
     * Jobs whose dispatch to an engine gave up on an open circuit / saturated bulkhead mid-run
     * — the item that tripped it got a BOUNDED wait-and-retry first (issue #101); this is only
     * populated when that bound is exceeded (R-SEM-11). Recorded by the tripped engine group,
     * consumed by {@link #runInternal} to finish the job INTERRUPTED (partial) rather than COMPLETED —
     * undispatched work is surfaced for a "continue as new job", never silently burned as failures.
     */
    final Set<UUID> circuitPaused = ConcurrentHashMap.newKeySet();

    public BulkJobService(
            BulkJobRepository jobs,
            BulkJobItemRepository items,
            CorrectiveActionService actions,
            ProtectedInstanceRepository protectedInstances,
            io.inspector.audit.ProtectedDefinitionRepository protectedDefinitions,
            AuditService audit,
            EngineRegistry registry,
            ProcessApiClient client,
            Clock clock,
            InspectorProperties props,
            ApplicationEventPublisher events,
            DangerousActionReauthGate reauth,
            GuardedCaller guardedCaller,
            MeterRegistry metrics) {
        this.jobs = jobs;
        this.items = items;
        this.actions = actions;
        this.protectedInstances = protectedInstances;
        this.protectedDefinitions = protectedDefinitions;
        this.audit = audit;
        this.registry = registry;
        this.client = client;
        this.clock = clock;
        this.props = props;
        this.events = events;
        this.reauth = reauth;
        this.guardedCaller = guardedCaller;
        this.metrics = metrics;
        // OPERATIONS.md §2 (issue #96): live count of non-terminal jobs — a gauge, not a counter,
        // since RUNNING is a point-in-time state, not a monotonic event.
        Gauge.builder("bulk_jobs_running", jobs, r -> r.countByState(BulkJob.State.RUNNING))
                .description("Bulk jobs currently in the RUNNING state")
                .register(metrics);
        this.runner = new BulkJobRunner(this);
        this.submitter = new BulkJobSubmitter(this, runner);
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

    /**
     * Package-door for the tier-4 destructive-bulk wizard (issue #100), used ONLY by
     * {@link DestructiveBulkService} — which has already run its own refuse-unscoped, ADMIN
     * hard-gate, and typed-count-drift rails against a FRESH resolution before calling this.
     * Still converges on the same private {@code submit} below, so the dangerous-set reauth gate
     * (line below) applies exactly once here too — the gate cannot be bypassed by ANY door.
     */
    BulkDtos.BulkJobDto submitDestructive(
            BulkDtos.BulkSubmitRequest request,
            Authentication auth,
            Map<String, Object> extraEnvelopePayload,
            String scopeLabel) {
        return submit(
                request,
                auth,
                extraEnvelopePayload,
                BulkJob.ITEM_CAP,
                BulkJob.ScopeKind.FILTER,
                scopeLabel,
                DESTRUCTIVE_BULK_VERBS,
                "Destructive bulk supports terminate-delete only (delete-deadletter-at-scale is a"
                        + " documented fast-follow). Nothing happened.");
    }

    /** Completed-history deletes. Cap is the filter cap. Per-id dispatch, Stop already cancels the next item. */
    BulkDtos.BulkJobDto submitHousekeeping(
            BulkDtos.BulkSubmitRequest request,
            Authentication auth,
            Map<String, Object> extraEnvelopePayload,
            String scopeLabel) {
        return submit(
                request,
                auth,
                extraEnvelopePayload,
                BulkJob.FILTER_ITEM_CAP,
                BulkJob.ScopeKind.FILTER,
                scopeLabel,
                HISTORY_VERBS,
                "Housekeeping supports delete-history and delete-case-history. Nothing happened.");
    }

    private BulkDtos.BulkJobDto submit(
            BulkDtos.BulkSubmitRequest request,
            Authentication auth,
            Map<String, Object> extraEnvelopePayload,
            int itemCap,
            BulkJob.ScopeKind scopeKind,
            String scopeLabel) {
        return submit(
                request,
                auth,
                extraEnvelopePayload,
                itemCap,
                scopeKind,
                scopeLabel,
                BULK_VERBS,
                "Bulk supports retry-job, suspend, activate, trigger-timer, reassign-task and unassign-task —"
                        + " destructive bulk goes through the tier-4 wizard. Nothing happened.");
    }

    private BulkDtos.BulkJobDto submit(
            BulkDtos.BulkSubmitRequest request,
            Authentication auth,
            Map<String, Object> extraEnvelopePayload,
            int itemCap,
            BulkJob.ScopeKind scopeKind,
            String scopeLabel,
            Set<ActionVerb> allowedVerbs,
            String verbRefusalMessage) {
        // Dangerous-set freshness (IDP-SECURITY.md §5, R-SAFE-07): bulk is in the dangerous set
        // regardless of verb tier (it is the guard-tier-4 fan-out), so a stale OAuth2 session
        // re-authenticates ONCE — here at SUBMIT, where the session is live — never per persisted
        // item (a bulk job survives session expiry, R-SEM-10; the workers run this envelope's
        // targets under the already-freshness-checked submit). All FOUR entry doors (selection /
        // error-class / filter / destructive) converge on this overload, so the gate cannot be
        // bypassed by any of them.
        reauth.enforce(auth);
        return submitter.submit(
                request, auth, extraEnvelopePayload, itemCap, scopeKind, scopeLabel, allowedVerbs, verbRefusalMessage);
    }

    /* ------------------------------- reads ------------------------------- */

    /**
     * S2 (R-SAFE-17) scoped variant of the operations drawer's list read (issue #296). {@code
     * readableEngineIds} is the caller's {@link io.inspector.security.ReadScopeGate} set, resolved
     * by {@code BulkController} on the request thread — {@code null} = enforcement off =
     * unrestricted (legacy fleet-wide reads, unchanged). Under enforcement, a job touching NO
     * readable engine is omitted from the list entirely (never listed with a hidden item set);
     * a job spanning both readable and unreadable engines still appears, with {@link
     * #itemsFor} narrowing its tallies/items to the readable subset.
     */
    public List<BulkDtos.BulkJobDto> recent(int limit, Set<String> readableEngineIds) {
        int cap = Math.min(Math.max(limit, 1), 100);
        if (readableEngineIds != null && readableEngineIds.isEmpty()) {
            return List.of(); // caller can read no engine at all — nothing to list
        }
        List<BulkJob> jobList = readableEngineIds == null
                ? jobs.findAllByOrderBySubmittedAtDesc(PageRequest.of(0, cap))
                : jobs.findRecentByReadableEngineIds(readableEngineIds, PageRequest.of(0, cap));
        return jobList.stream()
                .map(job -> BulkDtos.BulkJobDto.of(job, itemsFor(job.getId(), readableEngineIds), false))
                .toList();
    }

    /**
     * S2 (R-SAFE-17) scoped variant of the detail read (issue #296): a job with zero items in the
     * caller's readable set answers the SAME 404 as an unknown id — existence of an out-of-scope
     * job is never leaked (the same doctrine {@code IncidentQueryService} uses for a
     * zero-intersection incident detail). A partially-in-scope job still returns, with {@link
     * #itemsFor} narrowing its item list/tallies to the readable subset only — the full per-item
     * {@code engine:instance} target list (the sharpest leak in issue #296) never reaches a
     * caller who cannot read that engine.
     */
    public BulkDtos.BulkJobDto get(UUID id, Set<String> readableEngineIds) {
        BulkJob job = jobs.findById(id)
                .orElseThrow(() ->
                        new GuardRefusedException(HttpStatus.NOT_FOUND, "bulk-job-unknown", "No bulk job " + id + "."));
        List<BulkJobItem> visible = itemsFor(id, readableEngineIds);
        if (readableEngineIds != null && visible.isEmpty()) {
            throw new GuardRefusedException(HttpStatus.NOT_FOUND, "bulk-job-unknown", "No bulk job " + id + ".");
        }
        return BulkDtos.BulkJobDto.of(job, visible, true);
    }

    /** {@code null} = unrestricted (all items); an empty readable set short-circuits to none. */
    private List<BulkJobItem> itemsFor(UUID jobId, Set<String> readableEngineIds) {
        if (readableEngineIds == null) {
            return items.findByJobIdOrderByOrdinal(jobId);
        }
        if (readableEngineIds.isEmpty()) {
            return List.of();
        }
        return items.findByJobIdAndEngineIdInOrderByOrdinal(jobId, readableEngineIds);
    }

    /** One engine's permit-pool saturation (issue #96, {@code GET /api/diag}). */
    public record EnginePermitSnapshot(String engineId, int available, int total) {}

    /**
     * Diagnostics: only engines that have dispatched at least one item THIS process report a
     * snapshot — {@link #enginePermits} is populated lazily on first dispatch, so an engine an
     * operator has never bulk-acted on legitimately has nothing to show (never a fabricated
     * "0/4 permits used").
     */
    public List<EnginePermitSnapshot> permitSnapshot() {
        int total = props.bulkOrDefault().enginePermitsOrDefault();
        return enginePermits.entrySet().stream()
                .map(e -> new EnginePermitSnapshot(e.getKey(), e.getValue().availablePermits(), total))
                .toList();
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
            verdict = precondition(engine, verb, item, job);
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

    private Verdict precondition(EngineConfig engine, ActionVerb verb, BulkJobItem item, BulkJob job) {
        switch (verb) {
            case RETRY_JOB -> {
                if (item.getJobRef() == null) {
                    return new Verdict(null, "no job reference recorded — needs L3 review of the audit trail");
                }
                Map<String, Object> dlq =
                        client.getJob(engine, CallPriority.INTERACTIVE, JobLaneKind.DEADLETTER, item.getJobRef());
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
                Map<String, Object> timer =
                        client.getJob(engine, CallPriority.INTERACTIVE, JobLaneKind.TIMER, item.getJobRef());
                return timer == null
                        ? new Verdict(BulkJobItem.State.ok, "verified: timer " + item.getJobRef() + " has fired")
                        : new Verdict(null, "timer " + item.getJobRef() + " is still queued — still-pending");
            }
            case SUSPEND, ACTIVATE -> {
                Map<String, Object> instance =
                        client.getRuntimeProcessInstance(engine, CallPriority.INTERACTIVE, item.getInstanceId());
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
            case DELETE_HISTORY -> {
                Map<String, Object> row =
                        client.getHistoricProcessInstance(engine, CallPriority.HOUSEKEEPING, item.getInstanceId());
                if (row == null) {
                    return new Verdict(BulkJobItem.State.ok, "verified: historic instance is gone");
                }
                if (row.get("endTime") == null) {
                    return new Verdict(null, "instance is still running");
                }
                return new Verdict(null, "historic instance is still present");
            }
            case REASSIGN_TASK, UNASSIGN_TASK -> {
                if (item.getJobRef() == null) {
                    return new Verdict(null, "no task reference recorded — needs L3 review of the audit trail");
                }
                Map<String, Object> task = client.getTask(engine, CallPriority.INTERACTIVE, item.getJobRef());
                if (task == null) {
                    return new Verdict(BulkJobItem.State.skipped, "task " + item.getJobRef() + " is no longer open");
                }
                String assignee = task.get("assignee") != null ? String.valueOf(task.get("assignee")) : null;
                if (verb == ActionVerb.UNASSIGN_TASK) {
                    return assignee == null
                            ? new Verdict(BulkJobItem.State.ok, "verified: task is unassigned")
                            : new Verdict(null, "task is still assigned to " + assignee);
                }
                return job.getAssignee() != null && job.getAssignee().equals(assignee)
                        ? new Verdict(BulkJobItem.State.ok, "verified: assignee is " + assignee)
                        : new Verdict(null, "assignee is " + assignee + " — reassign may not have applied");
            }
            default -> {
                return new Verdict(null, "no precondition predicate for '" + verb.path() + "' — needs L3");
            }
        }
    }

    /* ------------------------------- reconciliation (SPEC §7) ------------------------------- */

    private static final List<BulkJob.State> LIVE_BULK_STATES = List.of(BulkJob.State.PENDING, BulkJob.State.RUNNING);

    /** Startup sweep: no automatic resume, EVER — interrupted work is surfaced, not re-fired. */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileInterrupted() {
        List<BulkJob> stale;
        try {
            stale = jobs.findByStateIn(LIVE_BULK_STATES);
        } catch (RuntimeException e) {
            log.warn("bulk reconciliation sweep skipped — store unavailable: {}", e.toString());
            return;
        }
        for (BulkJob job : stale) {
            List<BulkJobItem> all = items.findByJobIdOrderByOrdinal(job.getId());
            settleInterrupted(
                    job,
                    all,
                    "in flight when the BFF stopped — may have applied; use Verify now",
                    "BFF stopped before dispatch",
                    "offer 'continue as new job'");
        }
    }

    /**
     * Best-effort recovery for a {@link #runInternal} failure — or an escaped {@link Error} the
     * outer {@link #run} catch didn't even see (#303 defect 2). Re-derives the job's CURRENT item
     * states fresh from the store (never assumes where {@link #runInternal} failed) and settles
     * it exactly as {@link #reconcileInterrupted()} would. If the store is unreachable here too,
     * the job is left RUNNING/PENDING for {@link #sweepStaleRunning()} or the next restart's
     * {@link #reconcileInterrupted()} — never silently swallowed.
     */
    void settleAfterRunFailure(UUID jobId) {
        try {
            BulkJob job = jobs.findById(jobId).orElse(null);
            if (job == null) {
                log.error("bulk job {} vanished from the store after a run() failure — cannot settle", jobId);
                return;
            }
            if (job.getState() != BulkJob.State.PENDING && job.getState() != BulkJob.State.RUNNING) {
                return; // already settled (e.g. finish() itself succeeded before the failure) — nothing to do
            }
            List<BulkJobItem> all = items.findByJobIdOrderByOrdinal(jobId);
            settleInterrupted(
                    job,
                    all,
                    "run() failed mid-flight — may have applied; use Verify now",
                    "run() failed before dispatch",
                    "run() failed outside its per-engine dispatch guards");
        } catch (RuntimeException e) {
            log.error(
                    "bulk job {} could not be settled after a run() failure — left for the periodic"
                            + " stale-RUNNING sweep",
                    jobId,
                    e);
        }
    }

    /**
     * Shared by {@link #reconcileInterrupted()}, {@link #settleAfterRunFailure}, and
     * {@link #sweepStaleRunning()}: settle every item still {@code dispatched}/{@code pending}
     * (an already-settled outcome is never touched), finish the job {@code INTERRUPTED}, close
     * its still-open envelope with the REAL per-item tally (if it can still be located — the
     * generic {@link io.inspector.audit.AuditPendingSweeper} is the backstop otherwise, delivering
     * its own javadoc's "M5 extends this sweep to bulk INTERRUPTED" promise honestly, #303), and
     * publish the change event so a browser already watching the job sees it.
     */
    private void settleInterrupted(
            BulkJob job, List<BulkJobItem> all, String dispatchedDetail, String pendingDetail, String logReason) {
        for (BulkJobItem item : all) {
            if (item.getState() == BulkJobItem.State.dispatched) {
                // In flight when this sweep fired: the engine may have applied it. NEVER re-fired.
                item.settle(BulkJobItem.State.unknown, dispatchedDetail, item.getAuditId(), clock.instant());
            } else if (item.getState() == BulkJobItem.State.pending) {
                item.settle(BulkJobItem.State.not_run, pendingDetail, null, clock.instant());
            }
        }
        items.saveAllAndFlush(all);
        BulkJob.State before = job.getState();
        job.finish(BulkJob.State.INTERRUPTED, clock.instant());
        jobs.saveAndFlush(job);
        cancelRequested.remove(job.getId());
        circuitPaused.remove(job.getId());
        audit.findPendingBulkEnvelope(job.getId())
                .ifPresentOrElse(
                        envelope -> runner.closeEnvelope(envelope, all),
                        () -> log.warn(
                                "bulk job {} swept INTERRUPTED but its envelope audit row could not be located — the"
                                        + " periodic PENDING sweeper will close it",
                                job.getId()));
        events.publishEvent(new BulkJobChangedEvent(job.getId()));
        log.warn(
                "bulk job {} ({} × {}) swept {} → INTERRUPTED — {}",
                job.getId(),
                job.getTotalItems(),
                job.getVerb(),
                before,
                logReason);
    }

    /* ------------------------------- stale-RUNNING sweep (#303) ------------------------------- */

    /** Same grace window {@link io.inspector.audit.AuditPendingSweeper} applies to its own PENDING bound. */
    private static final Duration STALE_RUNNING_GRACE = Duration.ofSeconds(60);

    /**
     * A generous multiplier over the worst single ITEM's own wait (its engine's write budget plus
     * one full circuit-pause bound, {@link #staleRunningBudget()}) — a healthy job, however large
     * or however staggered, keeps making steady per-item progress well inside this multiple of
     * that single-item worst case; only a job with NO forward motion at all for this long is
     * presumed genuinely stuck.
     */
    private static final int STALE_RUNNING_MARGIN = 3;

    /**
     * Delivers {@link io.inspector.audit.AuditPendingSweeper}'s own javadoc promise ("M5 extends
     * this sweep to bulk INTERRUPTED") honestly (#303): a bulk job that is {@code RUNNING} (or
     * still {@code PENDING} — never even reached {@link #runInternal}) with NO item-state progress
     * for longer than {@link #staleRunningBudget()} is settled exactly as
     * {@link #reconcileInterrupted()} settles a crash-restart job. NOTHING is ever resumed or
     * re-fired (RUNBOOK §3, PRODUCT-GUIDE) — this is a sweep, never a retry.
     *
     * <p>Runs ALONGSIDE, never instead of, {@link #reconcileInterrupted()}: that startup sweep
     * catches every RUNNING/PENDING job after an actual BFF restart (the JVM died, so any such job
     * is certainly dead); this periodic sweep is the safety net for a job stuck WITHOUT the
     * process dying — the one edge case {@link #run}'s own try/catch/finally (added alongside
     * this, #303) cannot cover, e.g. a genuine deadlock or an uncaught {@link Error} that somehow
     * evades even the {@code CompletableFuture.exceptionally} backstop in {@code submit}.
     *
     * <p><b>Accepted race window:</b> the staleness bound is deliberately generous so a healthy
     * job is never caught mid-stride, but it cannot be zero-risk against a job that legitimately
     * finishes in the same instant this sweep decides it is stale — {@link #sweepOneIfStale}'s
     * re-fetch-and-recheck immediately before mutating narrows that window to essentially nothing.
     * If both writers still land, the append-only guard trigger and this method's own idempotent
     * settle logic make the loser's write a harmless no-op or a logged, non-fatal rejection
     * ({@code AuditEntry}'s outcome column only accepts a further update from {@code PENDING} or
     * {@code unknown} — see {@code V1__init.sql}), never a corrupted row.
     */
    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT90S")
    public void sweepStaleRunning() {
        List<BulkJob> live;
        try {
            live = jobs.findByStateIn(LIVE_BULK_STATES);
        } catch (RuntimeException e) {
            log.warn("bulk stale-RUNNING sweep skipped — store unavailable: {}", e.toString());
            return;
        }
        if (live.isEmpty()) {
            return;
        }
        Instant cutoff = clock.instant().minus(staleRunningBudget());
        for (BulkJob job : live) {
            try {
                sweepOneIfStale(job, cutoff);
            } catch (RuntimeException e) {
                log.error("bulk stale-RUNNING sweep failed for job {}: {}", job.getId(), e.toString());
            }
        }
    }

    private void sweepOneIfStale(BulkJob job, Instant cutoff) {
        List<BulkJobItem> all = items.findByJobIdOrderByOrdinal(job.getId());
        Instant lastProgress = all.stream()
                .map(BulkJobItem::getFinishedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(job.getSubmittedAt());
        if (lastProgress.isAfter(cutoff)) {
            return; // healthy — still progressing within the bound
        }
        // Narrow the TOCTOU window against the job's own runInternal() finishing concurrently:
        // re-fetch and re-confirm it is STILL live immediately before mutating (class javadoc).
        BulkJob fresh = jobs.findById(job.getId()).orElse(null);
        if (fresh == null || (fresh.getState() != BulkJob.State.PENDING && fresh.getState() != BulkJob.State.RUNNING)) {
            return;
        }
        settleInterrupted(
                fresh,
                all,
                "no item-state progress within the staleness bound (" + staleRunningBudget()
                        + ") — the job appears stuck; may have applied, use Verify now",
                "swept: the job appeared stuck and never reached this item",
                "no item-state progress within the staleness bound — offer 'continue as new job'");
    }

    /**
     * {@code GRACE + margin × (maxWriteMs + circuitPauseMaxMs)} — documented per the #303 plan:
     * conservative enough that a healthy job, however large or staggered, never crosses it while
     * still making progress (every completed/failed/skipped/unknown item bumps the "last
     * progress" clock — see {@link #sweepOneIfStale}), while a job with no forward motion at all
     * for this long is presumed genuinely stuck.
     */
    private Duration staleRunningBudget() {
        long perItemWorstCaseMs = maxWriteMs() + props.bulkOrDefault().circuitPauseMaxMsOrDefault();
        return STALE_RUNNING_GRACE.plus(Duration.ofMillis(perItemWorstCaseMs * STALE_RUNNING_MARGIN));
    }

    private long maxWriteMs() {
        return props.engines().stream()
                .map(e -> e.timeoutsOrDefault().write())
                .max(Integer::compareTo)
                .orElse(10_000);
    }
}
