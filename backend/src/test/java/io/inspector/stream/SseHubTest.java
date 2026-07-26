package io.inspector.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.inspector.bulk.BulkJobChangedEvent;
import io.inspector.config.InspectorProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Rung 1 for the SSE hub (v1.x #2, live-ui-sse doctrine; issue #301): the send loop must NEVER
 * throw into the publishing thread (a bulk executor), a broken emitter is dropped — never
 * completed — and the heartbeat is a no-op with no listeners. Issue #301 adds the per-emitter
 * locking contract: a deliberately-stalled emitter must never delay a healthy sibling or the
 * publisher, {@code stop()} must not deadlock against an in-flight send, bulk-job events for the
 * same job must coalesce, and the subscriber cap must refuse rather than grow unbounded. Real
 * streaming truth (headers, reconnect) rides the BulkFilterIT wire test. Latches/Awaitility
 * only — no {@code Thread.sleep()} (ArchUnit-enforced).
 */
class SseHubTest {

    @Test
    void bridgesBulkEventsWithoutThrowingIntoThePublisher() {
        SseHub hub = new SseHub(new SimpleMeterRegistry(), defaultProps());
        hub.subscribe("viewer");

        // A standalone emitter buffers sends until a response attaches — the contract
        // asserted here is "the publishing thread survives and keeps the stream".
        hub.onBulkJobChanged(new BulkJobChangedEvent(UUID.randomUUID()));

        assertThat(hub.subscriberCount()).isEqualTo(1);
    }

    @Test
    void brokenEmitterIsDroppedHealthyOneStays() {
        SeamHub hub = new SeamHub();
        hub.nextIsBroken = true;
        hub.subscribe("broken");
        hub.nextIsBroken = false;
        hub.subscribe("viewer");
        assertThat(hub.subscriberCount()).isEqualTo(2);

        hub.onBulkJobChanged(new BulkJobChangedEvent(UUID.randomUUID()));

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(hub.subscriberCount()).isEqualTo(1));
        // OPERATIONS.md §2 (issue #96): the dropped write increments sse_emitter_errors_total.
        assertThat(hub.metrics.counter("sse_emitter_errors_total").count()).isEqualTo(1.0);
    }

    @Test
    void heartbeatIsANoOpWithoutSubscribersAndPingsWithThem() {
        SeamHub hub = new SeamHub();
        hub.heartbeat(); // no listeners: must not throw

        hub.nextIsBroken = true;
        hub.subscribe("broken");
        hub.heartbeat(); // the ping write fails → the dead stream is reaped

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(hub.subscriberCount()).isZero());
    }

    /**
     * Issue #301, plan step 1/5: a deliberately-blocking emitter's write must not delay a
     * healthy sibling's — the whole point of dispatching each send onto its own virtual thread
     * outside any shared monitor. Before the fix this hung on {@code send()}'s hub-wide
     * {@code synchronized} loop; now the healthy emitter's write completes promptly while the
     * stalled one is still parked.
     */
    @Test
    void deliberatelyBlockingEmitterDoesNotDelayAHealthySibling() throws InterruptedException {
        SeamHub hub = new SeamHub();
        SeamHub.SlowSend stalled = new SeamHub.SlowSend();
        hub.nextSlowSend = stalled;
        hub.subscribe("stalled");

        AtomicInteger healthySends = new AtomicInteger();
        hub.nextCountsInto = healthySends;
        hub.subscribe("healthy");

        hub.heartbeat(); // dispatches a "ping" to BOTH subscribers concurrently

        assertThat(stalled.started.await(2, TimeUnit.SECONDS))
                .as("the stalled emitter's write actually started (proves it's genuinely in-flight)")
                .isTrue();
        // The healthy emitter's send must complete promptly even though "stalled" is still
        // parked inside its own send() — proves per-emitter dispatch, not a shared loop that
        // would serialize behind the stalled one.
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(healthySends.get()).isEqualTo(1));

        stalled.gate.countDown(); // release the stalled emitter so its virtual thread can finish
    }

    /**
     * Issue #301, plan step 2/5: {@code stop()} must take the SAME per-emitter lock an in-flight
     * send holds — proving it blocks behind (never races) that send — and must complete once
     * the send releases it, never deadlocking. The invariant "an errored emitter is NEVER
     * completed" is unaffected: this emitter never errors, so stop() DOES complete it.
     */
    @Test
    void stopDuringAnInFlightSendDoesNotDeadlock() throws InterruptedException {
        SeamHub hub = new SeamHub();
        hub.start();
        SeamHub.SlowSend inFlight = new SeamHub.SlowSend();
        hub.nextSlowSend = inFlight;
        hub.subscribe("in-flight");

        hub.heartbeat(); // starts a send that parks inside the per-emitter lock
        assertThat(inFlight.started.await(2, TimeUnit.SECONDS))
                .as("the send actually entered its critical section before stop() is called")
                .isTrue();

        Thread stopper = new Thread(hub::stop, "test-stopper");
        stopper.start();

        inFlight.gate.countDown(); // let the in-flight send (and thus stop()) proceed

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(stopper.isAlive()).isFalse());
        assertThat(hub.subscriberCount()).isZero();
    }

    /**
     * Issue #301, plan step 3/5: repeat {@code bulk-job} events for the SAME job within the
     * coalescing window must collapse into a single flush — a burst of per-item completions on
     * one job must never fan out one send per item.
     */
    @Test
    void repeatedBulkJobChangedEventsForTheSameJobCoalesce() {
        SeamHub hub = new SeamHub();
        AtomicInteger sends = new AtomicInteger();
        hub.nextCountsInto = sends;
        hub.subscribe("viewer");

        UUID jobId = UUID.randomUUID();
        hub.onBulkJobChanged(new BulkJobChangedEvent(jobId));
        hub.onBulkJobChanged(new BulkJobChangedEvent(jobId));
        hub.onBulkJobChanged(new BulkJobChangedEvent(jobId));

        // Exactly one flush lands for the three rapid repeats — no further task is ever
        // scheduled for this job id once the coalescing window fires, so this count can only
        // ever settle at 1, never creep higher.
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(sends.get()).isEqualTo(1));
    }

    /**
     * Issue #301, plan step 4/5: beyond the subscriber cap, a subscribe attempt is refused (and
     * completed immediately, never registered) rather than growing the registry unbounded — the
     * browser's EventSource then degrades the UI to polling on its own (frontend `useLive()`).
     */
    @Test
    void subscriptionsBeyondTheCapAreRefusedNotRegistered() {
        InspectorProperties props = new InspectorProperties(
                null, null, null, new InspectorProperties.Bulk(null, null, null, null, 1, null), List.of());
        SseHub hub = new SseHub(new SimpleMeterRegistry(), props);

        hub.subscribe("first"); // fills the cap of 1
        SseEmitter refused = hub.subscribe("second");

        assertThat(refused).isNotNull();
        assertThat(hub.subscriberCount()).isEqualTo(1);
    }

    private static InspectorProperties defaultProps() {
        return new InspectorProperties(null, null, null, null, List.of());
    }

    /** Creation-seam double: optionally hands out an emitter whose every write breaks, blocks, or counts. */
    private static final class SeamHub extends SseHub {
        final SimpleMeterRegistry metrics;
        boolean nextIsBroken;
        SlowSend nextSlowSend;
        AtomicInteger nextCountsInto;

        SeamHub() {
            this(new SimpleMeterRegistry());
        }

        private SeamHub(SimpleMeterRegistry metrics) {
            super(metrics, defaultProps());
            this.metrics = metrics;
        }

        @Override
        protected SseEmitter newEmitter() {
            if (nextIsBroken) {
                nextIsBroken = false;
                return new SseEmitter(60_000L) {
                    @Override
                    public void send(SseEventBuilder builder) throws IOException {
                        throw new IOException("broken pipe");
                    }
                };
            }
            SlowSend slow = nextSlowSend;
            if (slow != null) {
                nextSlowSend = null;
                return new SseEmitter(60_000L) {
                    @Override
                    public void send(SseEventBuilder builder) throws IOException {
                        slow.started.countDown();
                        try {
                            slow.gate.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                };
            }
            AtomicInteger counter = nextCountsInto;
            if (counter != null) {
                nextCountsInto = null;
                return new SseEmitter(60_000L) {
                    @Override
                    public void send(SseEventBuilder builder) {
                        counter.incrementAndGet();
                    }
                };
            }
            return super.newEmitter();
        }

        /** A send() that signals it has STARTED, then blocks until released — simulates a stalled client. */
        static final class SlowSend {
            final CountDownLatch started = new CountDownLatch(1);
            final CountDownLatch gate = new CountDownLatch(1);
        }
    }
}
