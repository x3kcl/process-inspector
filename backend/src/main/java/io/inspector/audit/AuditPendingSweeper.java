package io.inspector.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.bulk.BulkJob;
import io.inspector.bulk.BulkJobRepository;
import io.inspector.config.InspectorProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * report. Runs at startup (crash recovery) and periodically.
 *
 * <p><b>Bulk envelopes are excluded here while their job is still live (#303).</b> A bulk
 * envelope opens at submit and closes only when every item has settled — for a large,
 * staggered, or circuit-paced job that routinely (and healthily) outlives this sweep's PENDING
 * budget while still running. Sweeping it anyway used to stamp a perfectly healthy in-flight
 * job {@code unknown} mid-run purely because it hadn't finished yet — a recurring false
 * NEEDS-VERIFICATION alarm on the one surface whose entire value is that it never cries wolf.
 * This sweep now skips a PENDING bulk envelope for as long as its {@code bulk_job.state} is
 * {@code PENDING} or {@code RUNNING}: {@link io.inspector.bulk.BulkJobService#sweepStaleRunning()}
 * is the honest settlement path for a job that is GENUINELY stuck — it closes the envelope
 * itself, with the real per-item tally, the moment it declares the job {@code INTERRUPTED},
 * which also flips it out of this sweep's live-job exclusion set. This sweep therefore remains
 * the backstop only for an envelope whose job died in a way neither of those ever revisits
 * (e.g. the {@code bulk_job} row itself could not be updated either).
 */
@Component
public class AuditPendingSweeper {

    private static final Logger log = LoggerFactory.getLogger(AuditPendingSweeper.class);
    private static final Duration GRACE = Duration.ofSeconds(60);
    private static final List<BulkJob.State> LIVE_BULK_STATES = List.of(BulkJob.State.PENDING, BulkJob.State.RUNNING);

    private final AuditEntryRepository repository;
    private final BulkJobRepository bulkJobs;
    private final InspectorProperties properties;
    private final Clock clock;
    private final ObjectMapper mapper;

    public AuditPendingSweeper(
            AuditEntryRepository repository,
            BulkJobRepository bulkJobs,
            InspectorProperties properties,
            Clock clock,
            ObjectMapper mapper) {
        this.repository = repository;
        this.bulkJobs = bulkJobs;
        this.properties = properties;
        this.clock = clock;
        this.mapper = mapper;
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
        if (stale.isEmpty()) {
            return;
        }
        Set<UUID> liveBulkJobIds = liveBulkJobIds(stale);
        for (AuditEntry entry : stale) {
            UUID bulkJobId = bulkJobIdOf(entry);
            if (bulkJobId != null && liveBulkJobIds.contains(bulkJobId)) {
                // Still genuinely in flight — BulkJobService owns closing this envelope,
                // honestly, the moment it actually settles (class javadoc).
                continue;
            }
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

    /** Only queries the bulk-job store when at least one stale row is actually a bulk envelope. */
    private Set<UUID> liveBulkJobIds(List<AuditEntry> stale) {
        boolean anyBulkEnvelope = stale.stream().anyMatch(e -> bulkJobIdOf(e) != null);
        if (!anyBulkEnvelope) {
            return Set.of();
        }
        try {
            return bulkJobs.findByStateIn(LIVE_BULK_STATES).stream()
                    .map(BulkJob::getId)
                    .collect(Collectors.toSet());
        } catch (RuntimeException e) {
            // Can't tell which bulk jobs are still live — fail toward the pre-#303 behavior
            // (sweep it) rather than risk a genuinely dead envelope never being swept at all; a
            // false-positive sweep of a still-healthy job self-heals at its own finish() (close()
            // has no state guard against a fresher final outcome overwriting `unknown` — see the
            // V1__init.sql append-only trigger, which permits exactly that transition).
            log.warn("audit PENDING sweep: bulk-job liveness check unavailable, sweeping as usual: {}", e.toString());
            return Set.of();
        }
    }

    /**
     * Correlates on the payload's {@code bulkJobId} — the same join mechanics
     * {@code RelatedBulkJobsService} and {@link AuditEntryRepository#findRecentErrorClassBulkJobIds}
     * already rely on, since the entity's own {@code bulkJobId} column is never populated at
     * write time. Returns {@code null} for a non-bulk row or an unparseable/missing id — never
     * treated as belonging to a live bulk job either way (the conservative default already used
     * by {@link #liveBulkJobIds}'s own failure path: don't know ⇒ don't skip).
     */
    private UUID bulkJobIdOf(AuditEntry entry) {
        if (entry.getPayload() == null || !entry.getAction().startsWith("bulk:")) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(entry.getPayload()).get("bulkJobId");
            return node != null && node.isTextual() ? UUID.fromString(node.asText()) : null;
        } catch (RuntimeException | java.io.IOException e) {
            return null;
        }
    }

    private long maxWriteMs() {
        return properties.engines().stream()
                .map(e -> e.timeoutsOrDefault().write())
                .max(Integer::compareTo)
                .orElse(10_000);
    }
}
