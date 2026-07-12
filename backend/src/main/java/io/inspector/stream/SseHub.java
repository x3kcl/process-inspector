package io.inspector.stream;

import io.inspector.bulk.BulkJobChangedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 */
@Service
public class SseHub implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);
    private static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private final List<SubscribedEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Counter errors;
    private volatile boolean running;

    /**
     * OPERATIONS.md §2 (issue #96): {@code sse_emitters_active} (a live gauge over {@link
     * #subscriberCount()}) and {@code sse_emitter_errors_total} — a spike is RUNBOOK §7's "SSE
     * emitter errors spiking" alert (typically a reverse-proxy buffering misconfig after a
     * deploy; clients degrade to polling, not user-facing-critical).
     */
    public SseHub(MeterRegistry metrics) {
        Gauge.builder("sse_emitters_active", this, SseHub::subscriberCount)
                .description("Live SSE subscriber count")
                .register(metrics);
        this.errors = Counter.builder("sse_emitter_errors_total")
                .description("SSE emitter writes/registrations dropped due to a broken pipe or timeout")
                .register(metrics);
    }

    /** Session-bound subscription: the emitter carries the authenticated user for the record. */
    public SseEmitter subscribe(String user) {
        SseEmitter emitter = newEmitter();
        SubscribedEmitter subscribed = new SubscribedEmitter(emitter, user);
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

    /** The bulk progress feed: id-only — "job X changed", never the job body. */
    @EventListener
    public void onBulkJobChanged(BulkJobChangedEvent event) {
        send("bulk-job", event.jobId().toString());
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

    @Override
    public void stop() {
        running = false;
        synchronized (this) {
            for (SubscribedEmitter subscribed : emitters) {
                try {
                    subscribed.emitter().complete();
                } catch (RuntimeException e) {
                    log.debug("SSE emitter for {} did not complete cleanly: {}", subscribed.user(), e.toString());
                }
            }
            emitters.clear();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Never throws — it runs on the publishing thread (a bulk executor, the scheduler).
     * A failed write just drops that emitter; an errored emitter is NEVER completed
     * (complete() re-flushes and throws a secondary AsyncRequestNotUsableException).
     * Synchronized so concurrent events don't interleave inside one emitter's stream.
     */
    private synchronized void send(String eventName, String data) {
        for (SubscribedEmitter subscribed : emitters) {
            try {
                subscribed.emitter().send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException | RuntimeException e) {
                emitters.remove(subscribed);
                errors.increment();
                log.debug("SSE emitter for {} dropped: {}", subscribed.user(), e.toString());
            }
        }
    }

    private record SubscribedEmitter(SseEmitter emitter, String user) {}
}
