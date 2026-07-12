package io.inspector.stream;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.bulk.BulkJobChangedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Rung 1 for the SSE hub (v1.x #2, live-ui-sse doctrine): the send loop must NEVER throw
 * into the publishing thread (a bulk executor), a broken emitter is dropped — never
 * completed — and the heartbeat is a no-op with no listeners. Real streaming truth
 * (headers, reconnect) rides the BulkFilterIT wire test.
 */
class SseHubTest {

    @Test
    void bridgesBulkEventsWithoutThrowingIntoThePublisher() {
        SseHub hub = new SseHub(new SimpleMeterRegistry());
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

        assertThat(hub.subscriberCount()).isEqualTo(1);
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

        assertThat(hub.subscriberCount()).isZero();
    }

    /** Creation-seam double: optionally hands out an emitter whose every write breaks. */
    private static final class SeamHub extends SseHub {
        final SimpleMeterRegistry metrics;
        boolean nextIsBroken;

        SeamHub() {
            this(new SimpleMeterRegistry());
        }

        private SeamHub(SimpleMeterRegistry metrics) {
            super(metrics);
            this.metrics = metrics;
        }

        @Override
        protected SseEmitter newEmitter() {
            if (!nextIsBroken) {
                return super.newEmitter();
            }
            return new SseEmitter(60_000L) {
                @Override
                public void send(SseEventBuilder builder) throws IOException {
                    throw new IOException("broken pipe");
                }
            };
        }
    }
}
