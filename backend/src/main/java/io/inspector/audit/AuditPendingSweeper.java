package io.inspector.audit;

import io.inspector.config.InspectorProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The R-SEM-18 reconciler: a PENDING audit row older than the largest engine write budget
 * plus a grace period means the BFF died (or failed) between dispatch and close-out — the
 * honest outcome is {@code unknown}, surfaced under NEEDS VERIFICATION in the shift
 * report. Runs at startup (crash recovery) and periodically. M5 extends this same sweep
 * to bulk INTERRUPTED.
 */
@Component
public class AuditPendingSweeper {

    private static final Logger log = LoggerFactory.getLogger(AuditPendingSweeper.class);
    private static final Duration GRACE = Duration.ofSeconds(60);

    private final AuditEntryRepository repository;
    private final InspectorProperties properties;
    private final Clock clock;

    public AuditPendingSweeper(AuditEntryRepository repository, InspectorProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        sweep();
    }

    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT60S")
    public void sweepPeriodically() {
        sweep();
    }

    void sweep() {
        Instant cutoff = clock.instant().minus(GRACE).minusMillis(maxWriteMs());
        List<AuditEntry> stale;
        try {
            stale = repository.findByOutcomeAndTsBefore(AuditOutcome.PENDING, cutoff);
        } catch (RuntimeException e) {
            log.warn("audit PENDING sweep skipped — store unavailable: {}", e.toString());
            return;
        }
        for (AuditEntry entry : stale) {
            try {
                entry.close(
                        AuditOutcome.unknown,
                        null,
                        "swept to unknown by the reconciler: PENDING past the write budget — verify engine state",
                        false);
                repository.saveAndFlush(entry);
                log.warn(
                        "audit row {} ({} on {}:{}) swept PENDING → unknown — the action may or may not have applied",
                        entry.getId(),
                        entry.getAction(),
                        entry.getEngineId(),
                        entry.getInstanceId());
            } catch (RuntimeException e) {
                log.error("failed to sweep audit row {}: {}", entry.getId(), e.toString());
            }
        }
    }

    private long maxWriteMs() {
        return properties.engines().stream()
                .map(e -> e.timeoutsOrDefault().write())
                .max(Integer::compareTo)
                .orElse(10_000);
    }
}
