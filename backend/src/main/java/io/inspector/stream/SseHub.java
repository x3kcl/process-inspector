package io.inspector.stream;

import io.inspector.api.MdcPropagatingExecutors;
import io.inspector.bulk.BulkJobChangedEvent;
import io.inspector.config.InspectorProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The SSE emitter registry (v1.x #2, live-ui-sse doctrine): ONE stream per browser, domain
 * events bridged in as ID-ONLY signals — the client refetches its own JSON, the hub never
 * computes per-subscriber payloads. No initial event on connect (a buffered early send gets
 * replayed by Spring after the handler returns → broken-pipe noise); the client fetches its
 * first state itself. A 15s heartbeat keeps intermediary proxies from reaping idle streams.
 *
 * <p><b>Locking contract (issue #301)</b>: sending is dispatched, per emitter, onto its OWN
 * virtual thread ({@link #dispatch}) — never run on the publishing thread (a bulk item worker
 * holding an engine permit, or the heartbeat scheduler) and never inside a hub-wide monitor.
 * Each {@link SubscribedEmitter} carries its own {@link ReentrantLock} (never {@code
 * synchronized} — see CLAUDE.md's Java-21-VT rule): it serializes concurrent sends to the SAME
 * emitter (the original monitor's actual job — "concurrent events don't interleave") and
 * against a concurrent {@link #stop()} completing that same emitter, but a write stalled on one
 * subscriber's dead browser tab holds only that ONE lock/virtual-thread — it can never delay a
 * sibling subscriber's send or pace the publisher. An {@code errored} flag preserves "an errored
 * emitter is NEVER completed" across both paths. Bulk-progress events additionally decouple at
 * the source: {@link #onBulkJobChanged} never calls {@link #send} itself — it schedules a
 * coalesced flush per job id on {@link #coalescer}, so the publishing bulk worker only ever pays
 * for a map insert, and a burst of per-item events for one job collapses to one flush.
 */
@Service
public class SseHub implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);
    private static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private final List<SubscribedEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Counter errors;
    private final Counter refused;
    private final int subscriberCap;
    private final long coalesceMs;
    private volatile boolean running;

    /**
     * Issue #301: every send is dispatched onto its OWN fresh virtual thread so a stalled
     * browser's blocking servlet write pins nothing but that one carrier — it can never delay a
     * sibling emitter's dispatch or the publisher (a bulk worker, the heartbeat scheduler) that
     * queued it. {@link MdcPropagatingExecutors} keeps the publishing request's correlationId on
     * the dispatched send's log lines (OPERATIONS §2).
     */
    private final ExecutorService dispatch = MdcPropagatingExecutors.newVirtualThreadPerTaskExecutor();

    /**
     * Issue #301: bulk-job events are id-only and bursty (one per settled item); a single
     * daemon thread schedules a coalesced flush per job id {@link #coalesceMs} after the FIRST
     * event for that id, so repeats within the window collapse into one flush. Never an
     * unbounded queue — {@link #pendingBulkJobs} admits at most one pending flush per distinct
     * in-flight job id, and the coalescer's own work (a map removal + a call to {@link #send},
     * itself non-blocking) never touches a servlet write.
     */
    private final ScheduledExecutorService coalescer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sse-coalescer");
        thread.setDaemon(true);
        return thread;
    });

    private final Set<UUID> pendingBulkJobs = ConcurrentHashMap.newKeySet();

    /**
     * OPERATIONS.md §2 (issue #96): {@code sse_emitters_active} (a live gauge over {@link
     * #subscriberCount()}) and {@code sse_emitter_errors_total} — a spike is RUNBOOK §7's "SSE
     * emitter errors spiking" alert (typically a reverse-proxy buffering misconfig after a
     * deploy; clients degrade to polling, not user-facing-critical). {@code
     * sse_subscriptions_refused_total} (issue #301) counts cap refusals — see {@link #subscribe}.
     */
    public SseHub(MeterRegistry metrics, InspectorProperties props) {
        Gauge.builder("sse_emitters_active", this, SseHub::subscriberCount)
                .description("Live SSE subscriber count")
                .register(metrics);
        this.errors = Counter.builder("sse_emitter_errors_total")
                .description("SSE emitter writes/registrations dropped due to a broken pipe or timeout")
                .register(metrics);
        this.refused = Counter.builder("sse_subscriptions_refused_total")
                .description("SSE subscribe attempts refused because the live subscriber cap was reached")
                .register(metrics);
        InspectorProperties.Bulk bulk = props.bulkOrDefault();
        this.subscriberCap = bulk.sseSubscriberCapOrDefault();
        this.coalesceMs = bulk.sseCoalesceMsOrDefault();
    }

    /** Session-bound subscription: the emitter carries the authenticated user for the record. */
    public SseEmitter subscribe(String user) {
        if (emitters.size() >= subscriberCap) {
            // Issue #301: refuse rather than grow the registry unbounded. Completing the emitter
            // immediately (never adding it to `emitters`) looks to EventSource like an ordinary
            // server-initiated disconnect — NOT the "bad status/content-type" case that fails
            // the connection for good — so the browser's own reconnection logic keeps retrying
            // on its normal schedule while the UI's `live` flag drops and callers fall back to
            // polling (frontend `useLive()`/`useBulkJobs(open, live)`; OPERATIONS §2 / RUNBOOK §7
            // already treat SSE loss as degrade-to-polling).
            refused.increment();
            log.debug("SSE subscribe by {} refused — at cap ({} live)", user, subscriberCap);
            SseEmitter emitter = newEmitter();
            emitter.complete();
            return emitter;
        }
        SseEmitter emitter = newEmitter();
        SubscribedEmitter subscribed = new SubscribedEmitter(emitter, user, new ReentrantLock(), new AtomicBoolean());
        emitter.onCompletion(() -> emitters.remove(subscribed));
        emitter.onTimeout(() -> emitters.remove(subscribed));
        emitter.onError(e -> {
            emitters.remove(subscribed);
            errors.increment();
        });
        emitters.add(subscribed);
        log.debug("SSE subscribe by {} — {} live stream(s)", user, emitters.size());
        return emitter;
    }

    /**
     * The bulk progress feed: id-only — "job X changed", never the job body. Issue #301: never
     * calls {@link #send} on the publishing thread (a bulk item worker, inside the try that
     * holds the engine dispatch permit until its finally releases it) — it only ever schedules
     * a coalesced flush, so the worker pays for a map insert and returns immediately.
     */
    @EventListener
    public void onBulkJobChanged(BulkJobChangedEvent event) {
        UUID jobId = event.jobId();
        if (!pendingBulkJobs.add(jobId)) {
            return; // a flush for this job is already scheduled within the coalescing window
        }
        coalescer.schedule(
                () -> {
                    pendingBulkJobs.remove(jobId);
                    send("bulk-job", jobId.toString());
                },
                coalesceMs,
                TimeUnit.MILLISECONDS);
    }

    /** Keep-alive ping every 15s so proxies don't reap quiet streams; no-op with no listeners. */
    @Scheduled(fixedDelay = 15_000)
    public void heartbeat() {
        if (emitters.isEmpty()) {
            return;
        }
        send("ping", "");
    }

    public int subscriberCount() {
        return emitters.size();
    }

    /** Creation seam — tests substitute an emitter with a broken pipe. */
    protected SseEmitter newEmitter() {
        return new SseEmitter(EMITTER_TIMEOUT_MS);
    }

    /* -------- shutdown (SmartLifecycle, default phase = MAX_VALUE) --------
     * An open stream is an ACTIVE request to the graceful-shutdown machinery — left
     * alone it holds the whole 30s grace period hostage on every stop. This lifecycle
     * stops BEFORE the web server's graceful-shutdown phase (DEFAULT_PHASE - 1024) and
     * completes every emitter; browsers just reconnect to the next BFF. */

    @Override
    public void start() {
        running = true;
    }

    /**
     * Issue #301: takes each emitter's OWN lock (never a hub-wide monitor, never all locks at
     * once — that would risk lock-ordering deadlock) so completion serializes against a
     * concurrent in-flight {@link #sendToOne}. The {@code errored} CAS keeps "an errored emitter
     * is NEVER completed" true even when a send for the SAME emitter races this loop: whichever
     * of {@link #sendToOne} / {@code stop()} wins the flag wins the right to act, the other is a
     * no-op.
     */
    @Override
    public void stop() {
        running = false;
        for (SubscribedEmitter subscribed : emitters) {
            subscribed.lock().lock();
            try {
                if (subscribed.errored().compareAndSet(false, true)) {
                    try {
                        subscribed.emitter().complete();
                    } catch (RuntimeException e) {
                        log.debug("SSE emitter for {} did not complete cleanly: {}", subscribed.user(), e.toString());
                    }
                }
            } finally {
                subscribed.lock().unlock();
            }
        }
        emitters.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @PreDestroy
    void shutdownExecutors() {
        dispatch.shutdownNow();
        coalescer.shutdownNow();
    }

    /**
     * Snapshots the emitter list and hands each one's write off to {@link #dispatch} — this
     * method itself never blocks and never touches a servlet write, so it is safe to call from
     * ANY thread, including the publisher (issue #301).
     */
    private void send(String eventName, String data) {
        for (SubscribedEmitter subscribed : emitters) {
            dispatch.execute(() -> sendToOne(subscribed, eventName, data));
        }
    }

    /**
     * Runs on its own virtual thread — never the publisher, never a shared monitor. The
     * per-emitter lock serializes this write against any OTHER concurrent send for the SAME
     * emitter (the original monitor's actual job) and against a concurrent {@link #stop()}, but
     * never against a DIFFERENT emitter's dispatch. Never throws into its caller (there is no
     * caller waiting — this always runs detached); a failed write just drops that emitter. An
     * errored emitter is NEVER completed (complete() re-flushes and throws a secondary
     * AsyncRequestNotUsableException) — the {@code errored} CAS also fences a concurrent
     * {@code stop()} off this same emitter.
     */
    private void sendToOne(SubscribedEmitter subscribed, String eventName, String data) {
        subscribed.lock().lock();
        try {
            if (subscribed.errored().get()) {
                return; // already dropped by a prior failed write, or completed by stop()
            }
            subscribed.emitter().send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException | RuntimeException e) {
            subscribed.errored().set(true);
            emitters.remove(subscribed);
            errors.increment();
            log.debug("SSE emitter for {} dropped: {}", subscribed.user(), e.toString());
        } finally {
            subscribed.lock().unlock();
        }
    }

    private record SubscribedEmitter(SseEmitter emitter, String user, ReentrantLock lock, AtomicBoolean errored) {}
}
