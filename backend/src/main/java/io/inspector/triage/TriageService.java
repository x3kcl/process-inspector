package io.inspector.triage;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.inspector.config.InspectorProperties;
import io.inspector.dto.TriageDashboardResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Thundering-herd protection for the triage landing (SPEC §4 Stage 0, ARCH §2.2): the
 * aggregation fan-out is cached for 20s (spec-pinned default) behind a single-flight
 * Caffeine loader — ten engineers opening the dashboard during a P1 produce ONE round of
 * engine queries, not ten. The response's {@code asOf} stamp is the aggregation instant,
 * so the UI renders cache age next to Refresh.
 *
 * {@code refresh=true} bypasses the cache, throttled to one bypass per 10s
 * (spec: 1/10s/user — global until BFF auth lands in M5, which is strictly stricter);
 * a throttled refresh silently serves the cached snapshot, observable via {@code asOf}.
 */
@Service
public class TriageService {

    private static final String KEY = "dashboard";

    private final Supplier<TriageDashboardResponse> loader;
    private final Cache<String, TriageDashboardResponse> cache;
    private final Clock clock;
    private final long refreshMinIntervalMs;
    private final AtomicLong lastRefreshEpochMs = new AtomicLong(0);

    @Autowired
    public TriageService(TriageAggregationService aggregator, InspectorProperties props, Clock clock, Ticker ticker) {
        this(
                aggregator::aggregate,
                Duration.ofSeconds(props.triageOrDefault().cacheTtlSOrDefault()),
                Duration.ofSeconds(props.triageOrDefault().refreshMinIntervalSOrDefault()),
                clock,
                ticker);
    }

    /** Test seam: fake ticker/clock and a counting loader — cache semantics at rung 1. */
    TriageService(
            Supplier<TriageDashboardResponse> loader,
            Duration ttl,
            Duration refreshMinInterval,
            Clock clock,
            Ticker ticker) {
        this.loader = loader;
        this.clock = clock;
        this.refreshMinIntervalMs = refreshMinInterval.toMillis();
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).ticker(ticker).build();
    }

    public TriageDashboardResponse dashboard(boolean refresh) {
        if (refresh && refreshAllowed()) {
            cache.invalidate(KEY);
        }
        return cache.get(KEY, k -> loader.get());
    }

    /** One bypass per interval; losers of the CAS race are throttled too — by design. */
    private boolean refreshAllowed() {
        long now = clock.millis();
        long previous = lastRefreshEpochMs.get();
        return now - previous >= refreshMinIntervalMs && lastRefreshEpochMs.compareAndSet(previous, now);
    }

    /**
     * Diagnostics (issue #96, {@code GET /api/diag}): age of the CURRENTLY cached snapshot —
     * {@code asMap()} peeks the cache without triggering the loader, so calling this never
     * counts as a fetch. Empty before the first {@link #dashboard} call this process has served.
     */
    public Optional<Duration> cacheAge() {
        TriageDashboardResponse cached = cache.asMap().get(KEY);
        return cached == null
                ? Optional.empty()
                : Optional.of(Duration.between(Instant.parse(cached.asOf()), clock.instant()));
    }
}
