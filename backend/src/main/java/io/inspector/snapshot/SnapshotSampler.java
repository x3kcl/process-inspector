package io.inspector.snapshot;

import io.inspector.config.InspectorProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The v2/M4 job-lane sampler (PLAN v2/M4, R-BAU-08). Runs the Stage-0 count aggregation on the
 * thin BACKGROUND resilience lane via a {@link SnapshotSource} and upserts one narrow row per
 * (engine, lane) into the {@code triage_snapshot} time-series, keyed to the current bucket. The
 * trend UI then reads history from Postgres — the snapshot store is the mechanism that keeps the
 * sparklines OFF the live engine.
 *
 * <p>Runs at startup (so a fresh boot seeds a first point) and on a {@code fixedDelay} cadence
 * (non-overlapping — a slow sample never stacks). A poll is NOT a mutation: no audit rail. The
 * store being unavailable degrades to a skipped cycle (one warn), never a failure — mirrors
 * {@link io.inspector.audit.AuditPendingSweeper}. Gated off in unit-test profiles via
 * {@code inspector.snapshot.enabled}.
 *
 * <p>After the store write, the cycle's whole {@link AggregationSample} is published as a
 * synchronous {@link AggregationSampledEvent} (ARCH §2.7) so the incident ledger — and any
 * future consumer — rides the SAME aggregation pass. The publish is failure-isolated: a
 * listener exception is caught and warned here, never breaking the return value or the cycle
 * (and disabling the sampler idles every downstream store — documented in INCIDENT-LEDGER §5).
 */
@Component
@ConditionalOnProperty(name = "inspector.snapshot.enabled", havingValue = "true", matchIfMissing = true)
public class SnapshotSampler {

    private static final Logger log = LoggerFactory.getLogger(SnapshotSampler.class);

    private final SnapshotSource source;
    private final SnapshotCountRepository repository;
    private final SnapshotBucket bucket;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public SnapshotSampler(
            SnapshotSource source,
            SnapshotCountRepository repository,
            InspectorProperties properties,
            Clock clock,
            ApplicationEventPublisher events) {
        this.source = source;
        this.repository = repository;
        this.bucket = new SnapshotBucket(properties.snapshotOrDefault().bucketWidthOrDefault());
        this.clock = clock;
        this.events = events;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sampleOnStartup() {
        sampleOnce();
    }

    @Scheduled(
            fixedDelayString = "${inspector.snapshot.sample-interval}",
            initialDelayString = "${inspector.snapshot.sample-interval}")
    public void samplePeriodically() {
        sampleOnce();
    }

    /**
     * Take one reading and upsert it at the current bucket. Returns the number of lane rows
     * written (0 when the source found nothing or the store was unavailable) — handy for tests
     * that drive a single cycle deterministically rather than waiting on the scheduler.
     */
    public int sampleOnce() {
        Instant at = bucket.floor(clock.instant());
        AggregationSample sample;
        try {
            sample = source.sample();
        } catch (RuntimeException e) {
            log.warn("snapshot sample skipped — aggregation failed: {}", e.toString());
            return 0;
        }
        List<EngineLaneCount> observations = sample.laneCounts();
        try {
            for (EngineLaneCount o : observations) {
                repository.upsert(o.engineId(), o.lane().name(), o.count(), at);
            }
        } catch (RuntimeException e) {
            log.warn("snapshot persist skipped — store unavailable: {}", e.toString());
            return 0;
        }
        if (!observations.isEmpty()) {
            log.debug("snapshot: wrote {} lane counts at bucket {}", observations.size(), at);
        }
        // AFTER the store write, on the successful path only: a failed aggregation or an
        // unavailable store must not fabricate an "observed" cycle for downstream consumers.
        try {
            events.publishEvent(new AggregationSampledEvent(sample, at));
        } catch (RuntimeException e) {
            log.warn("aggregation-sampled listener failed — snapshot cycle unaffected: {}", e.toString());
        }
        return observations.size();
    }
}
