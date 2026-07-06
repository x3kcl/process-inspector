package io.inspector.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import io.inspector.dto.TriageDashboardResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: the 20s cache + refresh-throttle semantics over a counting loader with a fake
 * ticker/clock — no wall time, no sleeps (engine-harness doctrine). The proof that no
 * engine HTTP happens on a cache hit lives in TriageAggregationIT (WireMock proxy journal).
 */
class TriageServiceTest {

    /** Advanceable nano ticker for Caffeine + a millis clock kept in lockstep. */
    private static final class FakeTime {
        final AtomicLong nanos = new AtomicLong(0);
        final Ticker ticker = nanos::get;

        Clock clock() {
            return new Clock() {
                @Override
                public Instant instant() {
                    return Instant.ofEpochMilli(nanos.get() / 1_000_000);
                }

                @Override
                public ZoneOffset getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(java.time.ZoneId zone) {
                    return this;
                }
            };
        }

        void advanceSeconds(long s) {
            nanos.addAndGet(Duration.ofSeconds(s).toNanos());
        }
    }

    private static TriageDashboardResponse response(int round) {
        return new TriageDashboardResponse("round-" + round, List.of(), Map.of(), Map.of(), List.of(), Map.of());
    }

    private static TriageService service(FakeTime time, AtomicInteger loads) {
        return new TriageService(
                () -> response(loads.incrementAndGet()),
                Duration.ofSeconds(20),
                Duration.ofSeconds(10),
                time.clock(),
                time.ticker);
    }

    @Test
    void servesFromCacheWithinTtlAndReloadsAfterExpiry() {
        FakeTime time = new FakeTime();
        AtomicInteger loads = new AtomicInteger();
        TriageService service = service(time, loads);

        assertThat(service.dashboard(false).asOf()).isEqualTo("round-1");
        time.advanceSeconds(19);
        assertThat(service.dashboard(false).asOf()).isEqualTo("round-1");
        assertThat(loads).hasValue(1);

        time.advanceSeconds(2); // 21s > TTL
        assertThat(service.dashboard(false).asOf()).isEqualTo("round-2");
        assertThat(loads).hasValue(2);
    }

    @Test
    void refreshBypassesTheCacheButIsThrottledToOnePerInterval() {
        FakeTime time = new FakeTime();
        AtomicInteger loads = new AtomicInteger();
        TriageService service = service(time, loads);

        time.advanceSeconds(100); // move past the epoch so the first refresh is allowed
        assertThat(service.dashboard(false).asOf()).isEqualTo("round-1");

        time.advanceSeconds(5); // inside TTL: a plain GET would be a hit
        assertThat(service.dashboard(true).asOf()).isEqualTo("round-2");

        time.advanceSeconds(5); // 5s since the last bypass: throttled, cached snapshot
        assertThat(service.dashboard(true).asOf()).isEqualTo("round-2");
        assertThat(loads).hasValue(2);

        time.advanceSeconds(5); // 10s since the last bypass: allowed again
        assertThat(service.dashboard(true).asOf()).isEqualTo("round-3");
    }

    @Test
    void concurrentColdStartsSingleFlightIntoOneLoad() throws Exception {
        FakeTime time = new FakeTime();
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        TriageService service = new TriageService(
                () -> {
                    loaderEntered.countDown();
                    try {
                        releaseLoader.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return response(loads.incrementAndGet());
                },
                Duration.ofSeconds(20),
                Duration.ofSeconds(10),
                time.clock(),
                time.ticker);

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var first = pool.submit(() -> service.dashboard(false));
            assertThat(loaderEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> service.dashboard(false)); // arrives mid-load
            releaseLoader.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).asOf()).isEqualTo("round-1");
            assertThat(second.get(5, TimeUnit.SECONDS).asOf()).isEqualTo("round-1");
            assertThat(loads)
                    .as("the thundering herd collapses to one engine round")
                    .hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
