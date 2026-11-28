package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.client.DmnApiClient;
import io.inspector.client.EngineInfoRecord;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.snapshot.SnapshotCountRepository;
import io.inspector.snapshot.StartByKeyRepository;
import io.inspector.support.TestEngines;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: the activity-history capability probe is an unfiltered count on the engine, so the health sweep must
 * not repeat it every cycle. One probe per engine until the engine's version changes, it goes unreachable and
 * returns, or the (default 6 h) TTL elapses; a failed probe is never cached.
 */
class EngineHealthHistoryProbeCacheTest {

    private static final Instant T0 = Instant.parse("2026-11-28T10:00:00Z");

    private final ProcessApiClient flowable = mock(ProcessApiClient.class);
    private final DmnApiClient dmn = mock(DmnApiClient.class);
    private final MutableClock clock = new MutableClock(T0);
    private final EngineConfig engine = TestEngines.engine("eng", "http://localhost:1");
    private EngineHealthService service;

    @BeforeEach
    void setUp() {
        when(flowable.engineInfo(any(), any())).thenReturn(new EngineInfoRecord("flowable", "7.1.0"));
        when(flowable.probeActivityHistory(any(), any())).thenReturn(true);
        service = new EngineHealthService(
                mock(EngineRegistry.class),
                flowable,
                dmn,
                clock,
                mock(SnapshotCountRepository.class),
                mock(StartByKeyRepository.class));
    }

    private void probes(int expected) {
        verify(flowable, times(expected)).probeActivityHistory(engine, CallPriority.INTERACTIVE);
    }

    @Test
    void manySweepsProbeTheEngineOnceAndKeepReportingTheCachedAnswer() {
        EngineHealth first = service.probeOne(engine);
        for (int i = 0; i < 9; i++) {
            clock.advance(Duration.ofSeconds(30));
            service.probeOne(engine);
        }
        probes(1);
        assertThat(first.capabilities().activityHistory()).isTrue();
        assertThat(service.probeOne(engine).capabilities().activityHistory()).isTrue();
    }

    @Test
    void aDefiniteNoIsCachedToo() {
        when(flowable.probeActivityHistory(any(), any())).thenReturn(false);
        service.probeOne(engine);
        EngineHealth again = service.probeOne(engine);
        probes(1);
        assertThat(again.capabilities().activityHistory()).isFalse();
    }

    @Test
    void aChangedEngineVersionProbesAgain() {
        service.probeOne(engine);
        when(flowable.engineInfo(any(), any())).thenReturn(new EngineInfoRecord("flowable", "7.2.0"));
        service.probeOne(engine);
        probes(2);
    }

    @Test
    void anEngineThatWentUnreachableAndCameBackProbesAgain() {
        service.probeOne(engine);
        doThrow(new IllegalStateException("connection refused")).when(flowable).engineInfo(any(), any());
        assertThat(service.probeOne(engine).reachable()).isFalse();
        doReturn(new EngineInfoRecord("flowable", "7.1.0")).when(flowable).engineInfo(any(), any());
        service.probeOne(engine);
        probes(2);
    }

    @Test
    void aFailedProbeIsNotCachedAndTheNextSweepRetries() {
        when(flowable.probeActivityHistory(any(), any()))
                .thenThrow(new IllegalStateException("read timed out"))
                .thenReturn(true);
        EngineHealth failed = service.probeOne(engine);
        assertThat(failed.error()).contains("capability probe failed");
        EngineHealth retried = service.probeOne(engine);
        probes(2);
        assertThat(retried.capabilities().activityHistory()).isTrue();
        service.probeOne(engine);
        probes(2);
    }

    @Test
    void theAnswerExpiresAfterTheTtlAndNotBefore() {
        service.probeOne(engine);
        clock.advance(Duration.ofHours(6).minusSeconds(1));
        service.probeOne(engine);
        probes(1);
        clock.advance(Duration.ofSeconds(1));
        service.probeOne(engine);
        probes(2);
    }

    @Test
    void enginesAreCachedIndependently() {
        EngineConfig other = TestEngines.engine("other", "http://localhost:2");
        service.probeOne(engine);
        service.probeOne(other);
        service.probeOne(engine);
        service.probeOne(other);
        probes(1);
        verify(flowable, times(1)).probeActivityHistory(other, CallPriority.INTERACTIVE);
    }

    @Test
    void aRegistryReprobeDoesNotReuseTheAnswerOfTheOldEndpoint() {
        EngineRegistry registry = mock(EngineRegistry.class);
        when(registry.all()).thenReturn(java.util.List.of(engine));
        EngineHealthService s = new EngineHealthService(
                registry, flowable, dmn, clock, mock(SnapshotCountRepository.class), mock(StartByKeyRepository.class));
        s.probeOne(engine);
        s.reprobe("eng");
        probes(2);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
